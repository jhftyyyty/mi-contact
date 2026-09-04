# Xiaomi Arabic Contacts Fix - v1.0.3 Debug 3

دي نسخة تشخيصية فقط لمشكلة المسافات في أسماء جهات الاتصال داخل Xiaomi Contacts.

## المطلوب
1. ارفع محتويات المشروع إلى GitHub.
2. شغّل GitHub Actions.
3. ثبّت APK الناتج.
4. فعّل الموديول في LSPosed مع `com.android.contacts`.
5. اعمل Force Stop لتطبيق جهات الاتصال.
6. افتح قائمة جهات الاتصال ومرر على أسماء فيها المشكلة مثل `ابو بكر`.
7. من LSPosed Logs ابحث عن `DialerUnlocker: DEBUG3`.

## ما الذي تسجله النسخة؟
- كل overloads لـ `TextView.setText` داخل `com.android.contacts` لأول عدد محدود من القيم.
- الـ class وresource ID والنص وعدد المسافات.
- TextViews الموجودة وقت ظهور نافذة Contacts.
- كل methods في `ContactListItemView` و`DefaultContactListAdapter` التي تستقبل Cursor أو نص أو أرقام.

النسخة لا تحاول إصلاح النص داخل Contacts أثناء التشخيص؛ الهدف معرفة المسار الحقيقي الذي يحذف المسافات.
