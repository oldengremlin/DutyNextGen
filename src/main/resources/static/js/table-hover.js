// Підсвітка рядка й стовпця таблиці графіка під курсором миші.
// Чистим CSS стовпець не підсвітити (нема способу дібрати клітини того
// самого стовпця в інших рядках без переліку кожного nth-child окремо) —
// тому невеликий делегований обробник подій замість цього.
(function () {
    function clearHighlight(table) {
        table.querySelectorAll('.hover-row, .hover-col').forEach(function (cell) {
            cell.classList.remove('hover-row', 'hover-col');
        });
    }

    document.querySelectorAll('.table-card > table').forEach(function (table) {
        table.addEventListener('mouseover', function (event) {
            var cell = event.target.closest('td, th');
            if (!cell || !table.contains(cell)) {
                return;
            }
            clearHighlight(table);

            var row = cell.parentElement;
            if (row.parentElement.tagName === 'TBODY') {
                Array.from(row.children).forEach(function (c) {
                    c.classList.add('hover-row');
                });
            }

            var index = cell.cellIndex;
            table.querySelectorAll('tr').forEach(function (r) {
                var c = r.children[index];
                if (c) {
                    c.classList.add('hover-col');
                }
            });
        });

        table.addEventListener('mouseleave', function () {
            clearHighlight(table);
        });
    });
})();
