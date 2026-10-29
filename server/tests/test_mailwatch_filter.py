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


def test_security_alerts_pass_to_triage():
    # تنبيهات أمان الحسابات الكبرى ليست نشرات — تُمرَّر للفرز الذكي («مهم») ولا تُحجب
    assert not f("no-reply@accounts.google.com")
    assert not f("noreply@appleid.apple.com")
    assert not f("no-reply@account.microsoft.com")
    assert f("no-reply@offers.google.com") is False  # يمر محلياً، والفرز الذكي يتخطاه كنشرة («تخطى»)


def test_real_correspondence_passes():
    assert not f("sara@company.com")
    assert not f("m.ahmed@gmail.com")
    assert not f("info@clinic.sa")      # قد تكون مراسلة حقيقية من منشأة صغيرة
    assert not f("hello@studio.design") # كذلك
    assert not f("")
    assert not f("not-an-email")
