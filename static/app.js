// Под какие URL стучимся. Совместимо с твоим шаблонным httpd.conf:
const FCGI_ENDPOINT = "/fcgi-bin/fastCGI-1.0-SNAPSHOT-jar-with-dependencies.jar";

const form = document.getElementById("point-form");
const errorsBox = document.getElementById("errors");
const resultsBody = document.querySelector("#results tbody");
const clearBtn = document.getElementById("clear");

// Простая клиентская валидация
function parseNum(id, name, min, max, opts = {}) {
    const raw = document.getElementById(id).value.trim().replace(",", ".");
    if (raw === "") throw `${name} пустой`;
    const v = Number(raw);
    if (!Number.isFinite(v)) throw `${name} не число`;
    if (opts.positive && v <= 0) throw `${name} должен быть > 0`;
    if (min !== undefined && v < min) throw `${name} < ${min}`;
    if (max !== undefined && v > max) throw `${name} > ${max}`;
    return v;
}

function showErrors(list) {
    errorsBox.innerHTML = list.map(e => `<div>• ${e}</div>`).join("");
}

function addRow(entry, index) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
    <td>${index}</td>
    <td>${entry.x}</td>
    <td>${entry.y}</td>
    <td>${entry.r}</td>
    <td>${entry.hit ? "✔" : "✖"}</td>
    <td>${entry.serverTime}</td>
    <td>${entry.execMicros}</td>`;
    resultsBody.appendChild(tr);
}

function renderHistory(history) {
    resultsBody.innerHTML = "";
    history.forEach((e, i) => addRow(e, i + 1));
}

form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    errorsBox.textContent = "";

    // Валидация на фронте
    const errs = [];
    let x, y, r;
    try { x = parseNum("x", "X", -5, 5); } catch (e) { errs.push(e); }
    try { y = parseNum("y", "Y", -5, 5); } catch (e) { errs.push(e); }
    try { r = parseNum("r", "R", 0, 5, { positive: true }); } catch (e) { errs.push(e); }

    if (errs.length) { showErrors(errs); return; }

    // Отправляем именно POST, как требуют условия
    const body = new URLSearchParams({ x: String(x), y: String(y), r: String(r) }).toString();

    try {
        const t0 = performance.now();
        const res = await fetch(FCGI_ENDPOINT, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
            body
        });
        const text = await res.text();

        // FastCGI сервер присылает JSON
        const data = JSON.parse(text);
        if (!data.ok) {
            showErrors(data.errors || ["Ошибка сервера"]);
            return;
        }
        // Перерисовать историю (свежая запись уже включена)
        renderHistory(data.history || []);
    } catch (e) {
        showErrors([`Сеть/сервер: ${e}`]);
    }
});

// Очистка таблицы (только на клиенте; при следующем запросе история снова приедет с сервера)
clearBtn.addEventListener("click", () => {
    resultsBody.innerHTML = "";
    errorsBox.textContent = "";
});
