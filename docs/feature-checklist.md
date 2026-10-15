# قائمة خصائص رفيق المرجعية

كل بند هنا يُختبر قبل أي إصدار جديد: آليًا في CI (اختبارات الخادم وفحص المحاكي) أو يدويًا.

## التقنيات
- التطبيق: Kotlin + Jetpack Compose (Material 3)، minSdk 26، target 35، نكهتان full و lite. الحالة: StateFlow في `LocalStore` و`ConversationStore`، والإعدادات المشفّرة في `Prefs` (EncryptedSharedPreferences).
- الخادم: Python/Starlette + SQLite (WAL، ترحيلات 1–7)، يعمل في Termux على جوال المستخدم، مع نفق Cloudflare اختياري.
- الذكاء: مزودون متعددون (Gemini / OpenAI / Claude / OpenRouter / مخصص) مع احتياطي، والمحادثة الصوتية المباشرة (Gemini Live).

## الشاشات ومسارات التنقل (أسماء open_screen ثابتة)
| المسار | الشاشة | الوظائف |
|---|---|---|
| onboarding | الترحيب | البدء الفوري (مفتاح محدود من الخادم) أو إدخال مفتاح |
| home | الرئيسية | التحية والتاريخ الهجري والميلادي، بطاقة رفيق AI (كتابة وصوت واقتراحات)، إنجاز اليوم، الموعد التالي، مهام اليوم بإجراءات سريعة، التذكيرات، زر «+» |
| calendar | التقويم | شريط الأسبوع، مواعيد التقويم، التذكيرات، المهام المستحقة |
| account | حسابي | الاسم، وضع العرض، كل الأقسام والإعدادات |
| notes | الملاحظات | تبويب الملاحظات في المهام |
| chat | المحادثة | كتابة/صوت/محادثة مباشرة، تأكيد الإجراءات الحساسة، ملف مُشارك، محادثة جديدة، أخطاء واضحة |
| tasks | المهام والملاحظات | اليوم/الأسبوع/المفتوحة/المتأخرة/المكتملة/الملاحظات، إضافة سريعة، إنجاز، أولوية، تأجيل، بدء التنفيذ، مهام فرعية، تعديل، حذف بتأكيد |
| projects | المشاريع | القوالب، اللوحة، المراحل، الصحة، الفريق والدعوات، مهامي في الفريق، تسجيل الوقت |
| business | الأعمال والمالية | الجهات، الدخل/المصروف، الفواتير والعروض، التنبيهات، تقرير Excel |
| search | البحث الشامل | بحث في بيانات الخادم |
| server | خادمي | الحالة، الرابط العام، المفاتيح، الربط برمز |
| settings | الإعدادات | الصلاحيات، الذكاء، الصوت، المحادثة المباشرة، الموجز والتنبيهات، الأمان والتأكيد، الذاكرة والبيانات، الملفات، زر النداء، فحص رفيق |
| memory / log / diagnostics | الذاكرة / سجل النشاط / الفحص | |

## الخدمات الخلفية
- AssistantService (الاستماع، كلمة التنبيه، التنفيذ)، VolumeButtonService (زر الصوت)، HarithNotificationListener (رسائل التطبيقات)، ReminderReceiver (التذكيرات + الغفوة والإنجاز من الإشعار)، BriefingReceiver (الموجز والفحوص الاستباقية كل 30 دقيقة)، ImportantAlerts، مزامنة العقل المشترك مع الخادم.

## أدوات المساعد الفعلية
create_task, update_task, complete_task, delete_task, list_tasks, create_note, update_note, delete_note, search_notes, create_reminder, list_reminders, cancel_reminder, get/create/update/delete_calendar_event, set_alarm, set_timer, alarms, save_memory, forget_memory, search_contacts, make_call, get_call_log, read_sms, send_sms (بتأكيد), read_app_messages, reply_to_app_message (بتأكيد), email_list/read/create_draft/send (بتأكيد), search_files, read_file, search_web, search_everything, open_app, open_system_screen, set_volume, set_flashlight, get_device_status, location, harith_server.

## قيود لا تتغير
- لا إرسال رسالة أو بريد أو رد دون تأكيد صريح. لا قراءة شاشة عبر إمكانية الوصول. لا ردود آلية.
- لا أرقام وهمية في الواجهة: كل المؤشرات محسوبة من بيانات المستخدم.
- المفاتيح في التخزين المشفّر فقط، ولا تظهر في السجلات.

## فحص المحاكي (CI): يجب أن تبقى كل الأسطر PASS
home 3.0، account، phone→server، server→phone، business، projects، team، server screen، pair، provisioning (قد يتعثر إذا انقطع adb في المحاكي، وهذا ليس من التطبيق)، و0 أعطال (FATAL EXCEPTION).
