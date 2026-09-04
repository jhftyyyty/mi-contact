# Xiaomi Arabic Contacts Fix

ده مشروع Xposed/LSPosed خاص بـ Xiaomi Contacts.

## الإصلاح

الإصدار 1.0.2 يستهدف مباشرة `ContactListItemView` داخل قائمة جهات الاتصال، ويستخدم URI الخاص بالصف لقراءة `DISPLAY_NAME` من نفس جهة الاتصال، ثم يعيد الاسم الأصلي فقط عندما تكون المشكلة هي اختفاء المسافات.

التطبيق بيحاول يحافظ على اسم جهة الاتصال **بالضبط كما هو محفوظ** في `ContactsContract.Contacts.DISPLAY_NAME`، حتى لو واجهة Xiaomi عرضته بعد إزالة المسافات.

مثال:

`محمد د فؤاد`

يُفترض يفضل ظاهر بنفس المسافات.

الموديول لا يغيّر قاعدة بيانات جهات الاتصال.

## مهم

- الـ package المستهدف للإصلاح: `com.android.contacts`
- المشروع يحتوي أيضًا على Hook وظيفة زر الـ Mute القديمة.
- بعد التثبيت من LSPosed، فعّل `Xiaomi Arabic Contacts Fix` لتطبيق `com.android.contacts`، ثم اعمل Force Stop لتطبيق جهات الاتصال أو أعد تشغيل الهاتف.

## بناء APK بدون خبرة

1. ارفع الملفات إلى GitHub Repository جديد.
2. اعمل Push إلى branch اسمه `main`.
3. GitHub Actions سيبني APK تلقائيًا.
4. الـ workflow ينشئ Release باسم `v1.0.2` تلقائيًا لو مش موجود، ثم يرفع الـ APK إليه.
5. كمان الـ APK بيتحفظ كـ Actions Artifact.
