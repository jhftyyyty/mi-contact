package com.example.automute

import android.content.Context
import android.graphics.Canvas
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val CONTACTS_PACKAGE = "com.android.contacts"
        private const val LOG_TAG = "DialerUnlocker"
        private const val DEBUG_MODE = true

        private val contactNameMap = ConcurrentHashMap<String, String>()
        private val contactNameById = ConcurrentHashMap<Long, String>()
        private val loadedContexts = ConcurrentHashMap.newKeySet<String>()
        private val hookedContactClasses = ConcurrentHashMap.newKeySet<String>()
        private val fixing = ThreadLocal.withInitial { false }
        private val debugSeen = ConcurrentHashMap.newKeySet<String>()
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

        if (isContacts) hookContactsNameDisplay(lpparam.classLoader)
        if (isDialer) hookMuteButton()
    }

    private fun hookContactsNameDisplay(classLoader: ClassLoader) {
        try {
            // Framework TextView hooks catch ordinary and recycled rows.
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fixing.get()) return
                        val view = param.thisObject as? TextView ?: return
                        val value = param.args[0] as? CharSequence ?: return
                        val fixed = restoreForTextView(view, value.toString())
                        if (fixed != null && fixed != value.toString()) param.args[0] = fixed
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
                        if (fixing.get()) return
                        val view = param.thisObject as? TextView ?: return
                        val value = param.args[0] as? CharSequence ?: return
                        val fixed = restoreForTextView(view, value.toString())
                        if (fixed != null && fixed != value.toString()) param.args[0] = fixed
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setTextKeepState",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fixing.get()) return
                        val view = param.thisObject as? TextView ?: return
                        val value = param.args[0] as? CharSequence ?: return
                        val fixed = restoreForTextView(view, value.toString())
                        if (fixed != null && fixed != value.toString()) param.args[0] = fixed
                    }
                }
            )

            if (DEBUG_MODE) installDebugTextHooks()

            // Xiaomi can change/rebind a row after setText(). Re-check immediately before draw.
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onDraw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        restoreTextView(param.thisObject as? TextView)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setContentDescription",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fixing.get()) return
                        val view = param.thisObject as? View ?: return
                        val value = param.args[0] as? CharSequence ?: return
                        val fixed = restoreForTextView(view as? TextView, value.toString())
                        if (fixed != null && fixed != value.toString()) param.args[0] = fixed
                    }
                }
            )

            hookXiaomiContactListClasses(classLoader)
            hookActivityResume(classLoader)

            XposedBridge.log("$LOG_TAG: Xiaomi Contacts v1.0.2 hooks installed")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: Contacts hook error: ${t.stackTraceToString()}")
        }
    }

    /**
     * Xiaomi's contact list has its own ContactListItemView.  The important difference
     * from the old version is that we first try to identify the exact contact represented
     * by the row, then read that contact's DISPLAY_NAME. This removes the ambiguity of a
     * global "AB" -> "A B" map when both forms exist in the address book.
     */
    private fun hookXiaomiContactListClasses(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.android.contacts.list.ContactListItemView",
            "com.android.contacts.list.DefaultContactListAdapter",
            "com.android.contacts.list.ContactListAdapter"
        )

        for (name in candidates) {
            try {
                val clazz = XposedHelpers.findClass(name, classLoader)
                if (!hookedContactClasses.add(name)) continue

                for (method in clazz.declaredMethods) {
                    val n = method.name.lowercase(Locale.ROOT)
                    if (!(n.contains("bind") || n.contains("name") || n.contains("contact") ||
                                n.contains("display") || n.contains("settext") || n.contains("setname"))) {
                        continue
                    }
                    try {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (DEBUG_MODE) debugLogContactMethod("BEFORE", method, param)
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                val v = param.thisObject as? View ?: return
                                if (DEBUG_MODE) debugLogContactMethod("AFTER", method, param)
                                fixContactRow(v)
                            }
                        })
                    } catch (_: Throwable) {
                        // Some synthetic/bridge methods cannot be hooked; the TextView hook remains.
                    }
                }
                XposedBridge.log("$LOG_TAG: Hooked Xiaomi class $name")
            } catch (_: Throwable) {
                // Class may not exist in another Contacts build.
            }
        }
    }

    private fun hookActivityResume(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? android.app.Activity ?: return
                        if (activity.packageName == CONTACTS_PACKAGE) {
                            contactNameMap.clear()
                            contactNameById.clear()
                            loadedContexts.remove(CONTACTS_PACKAGE)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun fixContactRow(view: View) {
        if (fixing.get()) return
        try {
            fixing.set(true)
            fixViewTree(view)
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: row fix error: ${t.message}")
        } finally {
            fixing.set(false)
        }
    }

    private fun fixViewTree(view: View) {
        if (view is TextView) restoreTextView(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) fixViewTree(view.getChildAt(i))
        }
    }

    private fun restoreTextView(view: TextView?) {
        if (view == null || fixing.get()) return
        val text = view.text?.toString() ?: return
        if (!looksLikeArabicOrMixedContactText(text)) return

        try {
            fixing.set(true)
            val restored = restoreForTextView(view, text)
            if (restored != null && restored != text) view.text = restored
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: draw restore error: ${t.message}")
        } finally {
            fixing.set(false)
        }
    }

    private fun restoreForTextView(view: TextView?, text: String): String? {
        if (text.isBlank() || !looksLikeArabicOrMixedContactText(text)) return null
        // In DEBUG mode we deliberately do not modify the displayed text.
        // This lets the logs capture Xiaomi's untouched input/output path.
        if (DEBUG_MODE && view?.context?.packageName == CONTACTS_PACKAGE) return null

        // 1) Exact row/contact identity if this TextView belongs to ContactListItemView.
        val contactId = findContactIdFromView(view)
        if (contactId != null && contactId > 0) {
            val original = getDisplayNameById(view?.context, contactId)
            if (original != null && whitespaceFreeKey(original) == whitespaceFreeKey(text)) {
                XposedBridge.log("$LOG_TAG: Restored contactId=$contactId: '$text' -> '$original'")
                return original
            }
        }

        // 2) Fallback for detail screens where there is no exposed row id.
        val context = view?.context ?: return null
        ensureContactNameCache(context)
        return contactNameMap[whitespaceFreeKey(text)]
    }

    private fun findContactIdFromView(view: View?): Long? {
        var current: Any? = view
        var depth = 0
        while (current != null && depth++ < 8) {
            val direct = readLongField(current, setOf("mcontactid", "contactid", "contact_id", "mcontact_id"))
            if (direct != null && direct > 0) return direct

            val uri = readUriField(current)
            if (uri != null) {
                val segments = uri.pathSegments
                for (i in segments.indices) {
                    if (segments[i].equals("contacts", true) && i + 1 < segments.size) {
                        segments[i + 1].toLongOrNull()?.let { if (it > 0) return it }
                    }
                }
            }

            current = if (current is View) current.parent else null
        }
        return null
    }

    private fun readLongField(obj: Any, wanted: Set<String>): Long? {
        for (field in allFields(obj.javaClass)) {
            val n = field.name.lowercase(Locale.ROOT)
            val isExact = wanted.contains(n)
            val isContactIdLike = n.contains("contact") && n.endsWith("id")
            if (!isExact && !isContactIdLike) continue
            try {
                field.isAccessible = true
                val value = field.get(obj)
                when (value) {
                    is Number -> return value.toLong()
                    is String -> value.toLongOrNull()?.let { return it }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun readUriField(obj: Any): Uri? {
        for (field in allFields(obj.javaClass)) {
            val n = field.name.lowercase(Locale.ROOT)
            if (!(n.contains("contacturi") || n == "uri" || n.contains("contact_uri"))) continue
            try {
                field.isAccessible = true
                val value = field.get(obj)
                if (value is Uri) return value
                if (value is String) return Uri.parse(value)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun allFields(clazz: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            result.addAll(c.declaredFields)
            c = c.superclass
        }
        return result
    }

    private fun getDisplayNameById(context: Context?, contactId: Long): String? {
        if (context == null) return null
        contactNameById[contactId]?.let { return it }
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId.toString()),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    if (!name.isNullOrEmpty()) {
                        contactNameById[contactId] = name
                        return name
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: exact contact query error: ${t.message}")
        }
        return null
    }

    private fun ensureContactNameCache(context: Context) {
        if (loadedContexts.contains(CONTACTS_PACKAGE)) return
        if (!loadedContexts.add(CONTACTS_PACKAGE)) return
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} IS NOT NULL AND ${ContactsContract.Contacts.DISPLAY_NAME} != ?",
                arrayOf(""),
                null
            )
            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else -1L
                    val original = if (nameIndex >= 0) it.getString(nameIndex) else null
                    if (id > 0 && !original.isNullOrEmpty()) contactNameById[id] = original
                    if (!original.isNullOrEmpty()) {
                        val key = whitespaceFreeKey(original)
                        if (key.isNotEmpty() && key != original) {
                            val previous = contactNameMap.putIfAbsent(key, original)
                            if (previous != null && previous != original) contactNameMap.remove(key)
                        }
                    }
                }
            }
            XposedBridge.log("$LOG_TAG: Loaded ${contactNameById.size} contact names")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: Contact cache error: ${t.message}")
        }
    }

    private fun whitespaceFreeKey(value: String): String {
        return value.replace(Regex("\\s+"), "")
            .replace("\u200C", "")
            .replace("\u200D", "")
    }

    private fun looksLikeArabicOrMixedContactText(text: String): Boolean {
        if (text.isBlank() || text.length > 160) return false
        var hasLetter = false
        var hasArabic = false
        for (ch in text) {
            if (Character.isLetter(ch)) hasLetter = true
            if (ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF') hasArabic = true
        }
        return hasLetter && hasArabic
    }

    /**
     * Diagnostic mode. It intentionally does not assume which Xiaomi class owns the
     * visible contact name. It records the actual TextView class, resource id, text,
     * parent chain, and matching DISPLAY_NAME values from ContactsContract.
     * Read the entries from LSPosed -> Logs after reproducing the problem once.
     */
    private fun installDebugTextHooks() {
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        debugLogTextView("setText BEFORE", param.thisObject as? TextView, param.args[0]?.toString())
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        debugLogTextView("setText AFTER", param.thisObject as? TextView, (param.thisObject as? TextView)?.text?.toString())
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setTextKeepState",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        debugLogTextView("setTextKeepState BEFORE", param.thisObject as? TextView, param.args[0]?.toString())
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        debugLogTextView("setTextKeepState AFTER", param.thisObject as? TextView, (param.thisObject as? TextView)?.text?.toString())
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "onDraw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? TextView ?: return
                        val text = v.text?.toString() ?: return
                        if (looksLikeArabicOrMixedContactText(text)) {
                            debugLogTextView("onDraw", v, text)
                        }
                    }
                }
            )
            XposedBridge.log("$LOG_TAG: DEBUG MODE ENABLED for com.android.contacts")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: DEBUG install error: ${t.stackTraceToString()}")
        }
    }

    private fun debugLogTextView(stage: String, view: TextView?, text: String?) {
        if (!DEBUG_MODE || view == null || text.isNullOrBlank()) return
        if (!looksLikeArabicOrMixedContactText(text)) return
        val normalized = whitespaceFreeKey(text)
        val resource = try {
            if (view.id == View.NO_ID) "NO_ID" else view.context.resources.getResourceName(view.id)
        } catch (_: Throwable) { "?" }
        val parentNames = ArrayList<String>()
        var p: Any? = view.parent
        var depth = 0
        while (p != null && depth++ < 5) {
            parentNames.add(p.javaClass.name)
            p = if (p is View) p.parent else null
        }
        val key = "TV|$stage|${view.javaClass.name}|$resource|$normalized"
        if (!debugSeen.add(key)) return

        XposedBridge.log(
            "$LOG_TAG: DEBUG $stage class=${view.javaClass.name} res=$resource " +
                    "text=${quoteForLog(text)} normalized=${quoteForLog(normalized)} " +
                    "parents=${parentNames.joinToString(" <- ")}"
        )

        debugFindMatchingContactNames(view.context, text)
    }

    private fun debugFindMatchingContactNames(context: Context, displayed: String) {
        try {
            val key = whitespaceFreeKey(displayed)
            if (key.isEmpty()) return
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} IS NOT NULL",
                null,
                null
            ) ?: return
            cursor.use {
                var count = 0
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext() && count < 8) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                    if (!name.isNullOrEmpty() && whitespaceFreeKey(name) == key) {
                        val id = if (idIndex >= 0) it.getLong(idIndex) else -1L
                        XposedBridge.log("$LOG_TAG: DEBUG CONTACT MATCH id=$id original=${quoteForLog(name)} displayed=${quoteForLog(displayed)}")
                        count++
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: DEBUG contact query error: ${t.message}")
        }
    }

    private fun debugLogContactMethod(stage: String, method: Method, param: XC_MethodHook.MethodHookParam) {
        try {
            val obj = param.thisObject
            val args = param.args.mapIndexed { i, a -> "arg$i=${debugValue(a)}" }.joinToString(", ")
            val key = "METHOD|$stage|${method.declaringClass.name}|${method.name}|$args"
            if (!debugSeen.add(key)) return
            XposedBridge.log("$LOG_TAG: DEBUG $stage ${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString { it.name }}) this=${obj?.javaClass?.name} $args")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG_TAG: DEBUG method log error: ${t.message}")
        }
    }

    private fun debugValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is CharSequence -> quoteForLog(value.toString().take(200))
            is Uri -> value.toString()
            is Number, is Boolean -> value.toString()
            is View -> "View(${value.javaClass.name},id=${value.id})"
            else -> value.javaClass.name
        }
    }

    private fun quoteForLog(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("'", "\\'")
            .let { "'$it'" }
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
            XposedHelpers.findAndHookMethod(View::class.java, "setAlpha", Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val alpha = param.args[0] as Float
                        if (alpha < 1.0f) {
                            val view = param.thisObject as View
                            if (isTargetView(view)) param.args[0] = 1.0f
                        }
                    }
                })
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (isTargetView(view)) {
                            if (!view.isEnabled) view.isEnabled = true
                            if (!view.isClickable) view.isClickable = true
                            if (view.alpha < 1.0f) view.alpha = 1.0f
                        }
                    }
                })
            XposedHelpers.findAndHookMethod(View::class.java, "performClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (isTargetMuteButton(view)) {
                            val am = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                            val currentState = am.isMicrophoneMute
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    if (am.isMicrophoneMute == currentState) am.isMicrophoneMute = !currentState
                                } catch (t: Throwable) {
                                    XposedBridge.log("$LOG_TAG: Audio toggle error: ${t.message}")
                                }
                            }, 50)
                        }
                    }
                })
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
