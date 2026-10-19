// صفحة التحميل: إن فُتحت من رابط دعوة (/join/CODE) تُظهر بطاقة الدعوة وتجهّز الرابط للنسخ
(function () {
  var m = location.pathname.match(/^\/join\/([A-Za-z0-9-]+)/);
  if (!m) return;
  var code = m[1].toUpperCase().replace(/[^A-Z0-9]/g, '');
  var card = document.getElementById('invite');
  var input = document.getElementById('invite-link');
  input.value = location.origin + '/join/' + code;
  document.getElementById('web-join').href = '/#join=' + code;
  card.hidden = false;
  // تحقق أن الدعوة صالحة (رسالة واضحة إن انتهت)
  fetch('/api/public/invite/' + code).then(function (r) { return r.json().then(function (j) { return [r.ok, j]; }); })
    .then(function (res) {
      if (!res[0]) {
        card.querySelector('h2').textContent = 'هذه الدعوة انتهت أو غير صحيحة';
        card.querySelector('ol').hidden = true;
        card.querySelector('.code').hidden = true;
      } else if (res[1].project) {
        card.querySelector('h2').textContent = 'دعوة للانضمام إلى مشروع «' + res[1].project + '»';
      }
    }).catch(function () {});
  document.getElementById('copy').addEventListener('click', function () {
    var btn = this;
    var done = function () { btn.textContent = 'نُسخ ✓'; setTimeout(function () { btn.textContent = 'انسخ الرابط'; }, 2000); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(input.value).then(done, function () { input.select(); });
    } else { input.select(); document.execCommand('copy'); done(); }
  });
})();
