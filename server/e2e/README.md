# اختبارات المتصفح الحقيقية للوحة رفيق

تشغّل خادمًا محليًا منفصلًا (بيانات اختبار في مجلد مؤقت، لا تلمس الإنتاج) ثم:

```
HARITH_DATA_DIR=/tmp/rafiq-e2e HARITH_PORT=8799 AI_PROVIDER=fake python -m harith run &
python server/e2e/web_e2e.py http://127.0.0.1:8799 /tmp/shots   # 78 بندًا: إعداد، دخول/خروج، كل الصفحات، CRUD، صلاحيات، مركز الإشعارات، جوال/لوحي
python server/e2e/browser_notice.py                               # تنبيه المتصفح والصفحة في الخلفية
```
