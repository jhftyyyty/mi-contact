# Xiaomi Arabic Contacts Fix — v1.0.3 DEBUG

دي نسخة تشخيصية. **لا تحاول إصلاح النص داخل `com.android.contacts`**؛ الهدف هو تسجيل المسار الحقيقي للنص حتى نعرف أين تختفي المسافة. وظيفة الـ Mute في تطبيق المكالمات تظل موجودة.

## الاختبار
1. ابنِ الـ APK من GitHub Actions وثبّته.
2. فعّل الموديول في LSPosed مع `com.android.contacts`.
3. اعمل Force Stop لجهات الاتصال.
4. افتح قائمة جهات الاتصال.
5. افتح/مرر على أسماء فيها مسافات، مثل `ابو بكر`.
6. افتح **LSPosed → Logs**.
7. انسخ كل السطور التي تبدأ بـ `DialerUnlocker: DEBUG` وأرسلها هنا.

## لو هتستخدم ADB بدل LSPosed
بعد فتح قائمة جهات الاتصال وتشغيل المشكلة، نفّذ:

```bash
adb logcat -d -s DialerUnlocker:D *:S
```

## ماذا يسجل؟
- الـ class الحقيقي للـ TextView الذي يستقبل الاسم.
- الـ resource ID والـ parent classes.
- النص في `setText BEFORE/AFTER` وقبل الرسم.
- النص بعد إزالة المسافات للتطابق فقط، بدون تغيير النص.
- الاسم الأصلي المطابق من `ContactsContract` مع Contact ID إن وُجد.
- methods في `ContactListItemView` والـ adapters التي يتم استدعاؤها أثناء إنشاء الصف.

**مهم:** هذه النسخة تشخيصية ولا تصلح المسافات. هذا مقصود حتى تكون الـ logs دليلًا على المكان الحقيقي للمشكلة. بعد إرسال الـ logs أعمل النسخة النهائية على الـ method الصحيح.


## v1.0.3 Debug

هذه النسخة تضيف Hook مباشر للدالة F3 الخاصة بـ DefaultContactListAdapter في Contacts 18.30.00.17، لأن النسخة السابقة لم تكن تسجلها بسبب اسمها المختصر. كما تسجل Cursor columns وقيمة DISPLAY_NAME ومسار ContactListItemView.G بدون تعديل النص أثناء التشخيص.
