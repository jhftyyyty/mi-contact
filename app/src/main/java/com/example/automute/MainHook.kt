package com.example.automute

import android.content.Context
import android.graphics.Canvas
import android.database.Cursor
import android.media.AudioManager
import android.provider.ContactsContract
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val CONTACTS_PACKAGE = "com.android.contacts"
        private const val LOG_TAG = "DialerUnlocker"

        // Cache: text as it appears without whitespace -> the original stored display name.
        private val contactNameMap = ConcurrentHashMap<String, String>()
        private val loadedContexts = ConcurrentHashMap.newKeySet<String>()
        private val loadingContacts = ThreadLocal.withInitial { false }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName.lowercase(Locale.ROOT)

        val isContacts = pkg == CONTACTS_PACKAGE
        val isDialer = pkg.contains("incallui") ||
                pkg.contains("dialer") ||
                pkg.contains("phone") ||
                pkg.contains("telecom")

        if (!isContacts && !isDialer) return

        XposedBridge.log("$LOG_TAG: Attached -> $pkg")

        if (isContacts) {
            hookContactsNameDisplay(lpparam.classLoader)
        }

        if (isDialer) {
            hookMuteButton()
        }
    }

    /**
     * Xiaomi Contacts keeps the real contact name in ContactsContract, but in some
     * Arabic layouts the displayed value can lose whitespace during presentation.
     *
     * We do not modify the provider/database. Instead, when a TextView in the Contacts
     * app is about to receive a string, we compare it with the stored display names and
     * restore the exact stored value when the only apparent difference is whitespace.
     */
    private fun hookContactsNameDisplay(classLoader: ClassLoader) {
        try {
            // Xiaomi Contacts can pass the contact name through several UI paths.
            // We therefore fix it both when TextView receives the text and immediately
            // before TextView draws it. This covers list rows, contact details and
            // screens that replace the text after our first hook.
            val restoreHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    restoreTextView(param.thisObject as? TextView)
                }
            }

            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (loadingContacts.get()) return
                        val value = param.args[0] as? CharSequence ?: return
                        val text = value.toString()
                        val view = param.thisObject as? TextView ?: return
                        val restored = restoreStoredSpacingForContext(view.context, text)
                        if (restored != null && restored != text) {
                            param.args[0] = restored
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (loadingContacts.get()) return
                        val value = param.args[0] as? CharSequence ?: return
                        val text = value.toString()
                        val view = param.thisObject as? TextView ?: return
                        val restored = restoreStoredSpacingForContext(view.context, text)
                        if (restored != null && restored != text) {
                            param.args[0] = restored
                        }
                    }
                }
            )

            // Covers code paths that modify the text after the normal setText hook.
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onDraw",
                Canvas::class.java,
                restoreHook
            )

            // Xiaomi may use setTextKeepState when recycling contact-list rows.
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setTextKeepState",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (loadingContacts.get()) return
                        val value = param.args[0] as? CharSequence ?: return
                        val view = param.thisObject as? TextView ?: return
                        val restored = restoreStoredSpacingForContext(view.context, value.toString())
                        if (restored != null && restored != value.toString()) {
                            param.args[0] = restored
                        }
                    }
                }
            )

            // Also restore a contact name used as accessibility/content description.
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setContentDescription",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (loadingContacts.get()) return
                        val view = param.thisObject as? View ?: return
                        val value = param.args[0] as? CharSequence ?: return
                        val restored = restoreStoredSpacingForContext(view.context, value.toString())
                        if (restored != null && restored != value.toString()) {
                            param.args[0] = restored
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? android.app.Activity ?: return
                        if (activity.packageName == CONTACTS_PACKAGE) {
                            contactNameMap.clear()
                            loadedContexts.remove(CONTACTS_PACKAGE)
                        }
                    }
                }
            )

            XposedBridge.log("$LOG_TAG: Xiaomi Contacts text/draw hooks installed")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: Contacts hook error: ${t.stackTraceToString()}")
        }
    }

    private fun restoreTextView(view: TextView?) {
        if (view == null || loadingContacts.get()) return
        try {
            val text = view.text?.toString() ?: return
            val restored = restoreStoredSpacingForContext(view.context, text)
            if (restored != null && restored != text) {
                view.text = restored
            }
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: draw restore error: ${t.message}")
        }
    }

    private fun restoreStoredSpacingForContext(context: Context, text: String): String? {
        if (!looksLikeArabicOrMixedContactText(text)) return null
        ensureContactNameCache(context)
        return restoreStoredSpacing(text)
    }

    private fun ensureContactNameCache(context: Context) {
        if (loadedContexts.contains(CONTACTS_PACKAGE)) return
        if (!loadedContexts.add(CONTACTS_PACKAGE)) return

        try {
            loadingContacts.set(true)
            val resolver = context.contentResolver
            val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)
            val cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.DISPLAY_NAME} IS NOT NULL AND ${ContactsContract.Contacts.DISPLAY_NAME} != ?",
                arrayOf(""),
                null
            )

            cursor?.use {
                val index = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (index >= 0) {
                    while (it.moveToNext()) {
                        val original = it.getString(index) ?: continue
                        val key = whitespaceFreeKey(original)
                        if (key.isEmpty() || key == original) continue

                        // Only restore a name when the whitespace-free form maps to one
                        // unique stored name. If two contacts collapse to the same key
                        // (e.g. "A B" and "AB"), skip it rather than guessing.
                        val previous = contactNameMap.putIfAbsent(key, original)
                        if (previous != null && previous != original) {
                            contactNameMap.remove(key)
                        }
                    }
                }
            }

            XposedBridge.log("$LOG_TAG: Loaded ${contactNameMap.size} unambiguous contact spacing entries")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: Contact cache error: ${t.message}")
        } finally {
            loadingContacts.set(false)
        }
    }

    private fun restoreStoredSpacing(text: String): String? {
        val key = whitespaceFreeKey(text)
        if (key.isEmpty()) return null
        return contactNameMap[key]
    }

    private fun whitespaceFreeKey(value: String): String {
        return value.replace(Regex("\\s+"), "")
            .replace('\u200C'.toString(), "")
            .replace('\u200D'.toString(), "")
    }

    private fun looksLikeArabicOrMixedContactText(text: String): Boolean {
        if (text.isBlank() || text.length > 160) return false
        var hasLetter = false
        var hasArabic = false
        for (ch in text) {
            if (Character.isLetter(ch)) hasLetter = true
            if (ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF') {
                hasArabic = true
            }
        }
        return hasLetter && hasArabic
    }

    private fun hookMuteButton() {
        try {
            val hookEnabled = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val enabled = param.args[0] as Boolean
                    if (!enabled) {
                        val view = param.thisObject as View
                        if (isTargetView(view)) {
                            param.args[0] = true
                            view.alpha = 1.0f
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(View::class.java, "setEnabled", Boolean::class.javaPrimitiveType, hookEnabled)
            XposedHelpers.findAndHookMethod(View::class.java, "setClickable", Boolean::class.javaPrimitiveType, hookEnabled)

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setAlpha",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val alpha = param.args[0] as Float
                        if (alpha < 1.0f) {
                            val view = param.thisObject as View
                            if (isTargetView(view)) param.args[0] = 1.0f
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (isTargetView(view)) {
                            if (!view.isEnabled) view.isEnabled = true
                            if (!view.isClickable) view.isClickable = true
                            if (view.alpha < 1.0f) view.alpha = 1.0f
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "performClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (isTargetMuteButton(view)) {
                            val am = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                            val currentState = am.isMicrophoneMute
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    if (am.isMicrophoneMute == currentState) {
                                        am.isMicrophoneMute = !currentState
                                        XposedBridge.log("$LOG_TAG: Force-toggled AudioManager to ${!currentState}")
                                    }
                                } catch (t: Throwable) {
                                    XposedBridge.log("$LOG_TAG: Audio toggle error: ${t.message}")
                                }
                            }, 50)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_TAG: Dialer hook error: ${e.stackTraceToString()}")
        }
    }

    private fun isTargetView(view: View): Boolean {
        val clsName = view.javaClass.simpleName.lowercase(Locale.ROOT)
        if (clsName.contains("button") || clsName.contains("toggle")) return true

        try {
            if (view.id != View.NO_ID) {
                val idName = view.context.resources.getResourceEntryName(view.id).lowercase(Locale.ROOT)
                if (idName.contains("mute") || idName.contains("mic") || idName.contains("audio") ||
                    idName.contains("btn") || idName.contains("action") || idName.contains("incall")) return true
            }
        } catch (_: Exception) {}

        try {
            val desc = view.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (desc.contains("mute") || desc.contains("mic") || desc.contains("كتم") ||
                desc.contains("ميك") || desc.contains("صوت")) return true
        } catch (_: Exception) {}

        return false
    }

    private fun isTargetMuteButton(view: View): Boolean {
        try {
            if (view.id != View.NO_ID) {
                val idName = view.context.resources.getResourceEntryName(view.id).lowercase(Locale.ROOT)
                if (idName.contains("mute") || idName.contains("mic") || idName.contains("audio")) return true
            }
        } catch (_: Exception) {}

        try {
            val desc = view.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (desc.contains("mute") || desc.contains("mic") || desc.contains("كتم") ||
                desc.contains("ميك") || desc.contains("صوت")) return true
        } catch (_: Exception) {}

        return false
    }
}
