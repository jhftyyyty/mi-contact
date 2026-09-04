# Xiaomi Arabic Contacts Fix v1.0.2

نسخة LSPosed/Xposed مخصصة لتطبيق Xiaomi Contacts (`com.android.contacts`).

## ما الجديد
- إصلاح عرض أسماء جهات الاتصال اعتمادًا على `DISPLAY_NAME` الأصلي.
- محاولة ربط صف جهة الاتصال بالـ Contact ID بدل الاعتماد على النص وحده.
- دعم `ContactListItemView` وعمليات إعادة ربط الصفوف (recycling).
- الحفاظ على المسافات الموجودة في الاسم المخزن بدون تعديل قاعدة البيانات.
- الإبقاء على إصلاح زر Mute الموجود في المشروع.

## الاستخدام
فعّل الموديول في LSPosed على `com.android.contacts`، ثم اعمل Force Stop لتطبيق جهات الاتصال وافتحه من جديد.
