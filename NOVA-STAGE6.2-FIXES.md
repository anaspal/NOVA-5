# NOVA 10.9.2 – مراجعة وإصلاحات

- **MoonshineAsrEngine**: `recognizer.createStream("")` → `recognizer.createStream()`. دالة `OfflineRecognizer.createStream` في واجهة sherpa-onnx لا تقبل وسيطاً، وكانت تكسر الترجمة.
- **MainActivity.requestAll**: إضافة طلب أذونات `READ_CONTACTS` و`CALL_PHONE` و`CAMERA`. كان أمر «نوفا اتصلي بفلان» لا يعمل لأن إذن جهات الاتصال لا يُطلب إلا من مفتاح «قراءة اسم المتصل».
- **app/build.gradle**: تثبيت `buildToolsVersion '33.0.2'` ليطابق ما يثبّته الـ workflow.
- **gradle.properties**: رفع ذاكرة Gradle إلى 3g (حزمة النماذج كبيرة).
- **android-apk.yml**:
  - كاش للـ AAR والنماذج (لا تُنزَّل في كل تشغيل).
  - ملف ترخيص Moonshine اختياري (كان `cp` يفشل البناء إن لم يوجد في الأرشيف).
  - رجوع لنسخة Dii الكاملة إن لم تتوفر نسخة int8.
  - خطوة تحقق من كل الملفات قبل البناء.
  - اسم الـ APK يتضمن رقم الإصدار: `NOVA-v10.9.2-debug.apk`.
