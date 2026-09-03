// Копіювання графіка в буфер обміну як готового HTML — щоб вставити прямо
// в лист (Thunderbird тощо), без кнопок керування й без рамки поточного
// дня (та має сенс лише на екрані, для одержувача листа — ні). Той самий
// список винятків, що й @media print (schedule.css), але для execCommand
// доводиться прибирати їх явно — @media print на живий клон не діє.
//
// document.execCommand('copy'), а не сучасний navigator.clipboard.write():
// останній вимагає secure context (HTTPS), а сайт поки що лише на http.
(function () {
    var button = document.getElementById('copy-for-email');
    if (!button) {
        return;
    }

    button.addEventListener('click', function () {
        var source = document.querySelector('.page');
        var clone = source.cloneNode(true);
        clone.querySelectorAll('.noprint').forEach(function (el) { el.remove(); });
        clone.querySelectorAll('.toolbar .nav-btn').forEach(function (el) { el.remove(); });
        clone.querySelectorAll('.today').forEach(function (el) { el.classList.remove('today'); });

        var holder = document.createElement('div');
        holder.style.position = 'fixed';
        holder.style.top = '0';
        holder.style.left = '-99999px';
        holder.appendChild(clone);
        document.body.appendChild(holder);

        var range = document.createRange();
        range.selectNodeContents(clone);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);

        var ok = false;
        try {
            ok = document.execCommand('copy');
        } catch (e) {
            ok = false;
        }

        selection.removeAllRanges();
        document.body.removeChild(holder);

        var original = button.textContent;
        button.textContent = ok ? 'Скопійовано!' : 'Не вдалося скопіювати';
        setTimeout(function () { button.textContent = original; }, 1500);
    });
})();
