"""فلتر المرسل الآلي/النشرات في مراقبة البريد — لا ردود مقترحة لهذه الفئة."""
from harith.mailwatch import _is_automated_sender as f


def test_linkedin_job_alerts_blocked():
    assert f("messages-noreply@linkedin.com")
    assert f("jobs-listings@linkedin.com")
    assert f("notifications-noreply@linkedin.com")
    assert f("invitations@linkedin.com")


def test_no_reply_patterns_blocked():
    assert f("noreply@example.com")
    assert f("no-reply@bank.sa")
    assert f("donotreply@shop.com")
    assert f("alerts@service.io")
    assert f("newsletter@brand.com")
    assert f("mailer-daemon@mail.net")
    assert f("jobs@recruiter-platform.com")




def test_real_correspondence_passes():
    assert not f("sara@company.com")
    assert not f("m.ahmed@gmail.com")
    assert not f("info@clinic.sa")      # قد تكون مراسلة حقيقية من منشأة صغيرة
    assert not f("hello@studio.design") # كذلك
    assert not f("")
    assert not f("not-an-email")


from harith.mailwatch import _auto_route as route


def test_delivery_failures_surface():
    assert route("mailer-daemon@googlemail.com", "Delivery Status Notification (Failure)", "فشل نهائي") == "surface"
    assert route("postmaster@mail.example", "Undelivered Mail Returned to Sender", "") == "surface"
    assert route("bounce@mailer.service.io", "رسالة", "") == "surface"


def test_security_billing_maintenance_surface():
    assert route("no-reply@accounts.google.com", "تنبيه بشأن أمان حساب", "") == "surface"
    assert route("no-reply@accounts.google.com", "تم استرداد حسابك على Google بنجاح", "") == "surface"
    assert route("no-reply@marketing.derayah.com", "صيانة مجدولة لأنظمة دراية المالية", "") == "surface"
    assert route("billing@cloud.sa", "فاتورتك لشهر أكتوبر", "") == "surface"


def test_marketing_jobs_newsletters_skip():
    assert route("jobalerts-noreply@linkedin.com", "وظائف جديدة", "") == "skip"
    assert route("newsletters-noreply@linkedin.com", "مهارات الأعمال", "") == "skip"
    assert route("recommendations@discover.pinterest.com", "تصميم الشعارات", "") == "skip"
    assert route("donotreply@jobalert.indeed.com", "وظيفة إضافية", "") == "skip"
    assert route("marketing@shop.com", "خصومات نهاية الأسبوع", "") == "skip"


def test_other_automated_goes_to_triage_without_drafts():
    assert route("no-reply@marketing.derayah.com", "الاستثمار مو مثل ماتتخيل!", "") == "triage"
    assert route("notifications@github.com", "[al-hiraf/Alharith_ai] Run failed: tests", "") == "triage"
    assert route("no-reply@accounts.google.com", "عروض جديدة من Google", "") == "triage"
