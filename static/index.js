"use strict";

const state = {
    x: null,
    y: null,
    r: null,
};

const table = document.getElementById("result-table");
const error = document.getElementById("error");
const possibleXs = new Set([-5, -4, -3, -2, -1, 0, 1, 2, 3]);
const possibleRs = new Set([1.0, 1.5, 2.0, 2.5, 3.0]);

const validateState = (state) => {
    if (isNaN(state.x) || !possibleXs.has(state.x)) {
        error.hidden = false;
        error.innerText = `X must be in [${[...possibleXs].join(" ,")}]`;
        throw new Error("Invalid state");
    }

    if (isNaN(state.y) || state.y < -5 || state.y > 3) {
        error.hidden = false;
        error.innerText = "Y must be in range [-5, 3]";
        throw new Error("Invalid state");
    }

    if (isNaN(state.r) || !possibleRs.has(state.r)) {
        error.hidden = false;
        error.innerText = `R must be in [${[...possibleRs].join(" ,")}]`;
        throw new Error("Invalid state");
    }

    error.hidden = true;
}


document.getElementById("x-block").addEventListener("change", (ev) => {
    state.x = parseFloat(ev.target.value);
});

document.getElementById("y").addEventListener("change", (ev) => {
    state.y = parseFloat(ev.target.value);
});

document.getElementById("r-block").addEventListener("change", (ev) => {
    state.r = parseFloat(ev.target.value);
});

document.getElementById("data-form").addEventListener("submit", async function (ev) {
    ev.preventDefault();
    validateState(state);

    const newRow = table.insertRow(-1);

    const rowX = newRow.insertCell(0);
    const rowY = newRow.insertCell(1);
    const rowR = newRow.insertCell(2);
    const rowTime = newRow.insertCell(3);
    const rowExecTime = newRow.insertCell(4);
    const rowResult = newRow.insertCell(5);

    const params = new URLSearchParams(state);

    const response = await fetch("/calculate", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded",
        },
        body: params.toString(),
    });

    const results = {
        x: state.x,
        y: state.y,
        r: state.r,
        execTime: "",
        time: "",
        result: false,
    };

    if (response.ok) {
        const result = await response.json();
        results.time = new Date(result.now).toLocaleString();
        results.execTime = `${result.time} ns`;
        results.result = result.result.toString();
    } else if (response.status === 400) {
        const result = await response.json();
        results.time = new Date(result.now).toLocaleString();
        results.execTime = "N/A";
        results.result = `error: ${result.reason}`;
    } else {
        results.time = "N/A";
        results.execTime = "N/A";
        results.result = "error"
    }

    const prevResults = JSON.parse(localStorage.getItem("results") || "[]");
    localStorage.setItem("results", JSON.stringify([...prevResults, results]));

    rowX.innerText = results.x.toString();
    rowY.innerText = results.y.toString();
    rowR.innerText = results.r.toString();
    rowTime.innerText = results.time;
    rowExecTime.innerText = results.execTime;
    rowResult.innerText = results.result;
});

const prevResults = JSON.parse(localStorage.getItem("results") || "[]");

prevResults.forEach(result => {
    const table = document.getElementById("result-table");

    const newRow = table.insertRow(-1);

    const rowX = newRow.insertCell(0);
    const rowY = newRow.insertCell(1);
    const rowR = newRow.insertCell(2);
    const rowTime = newRow.insertCell(3);
    const rowExecTime = newRow.insertCell(4);
    const rowResult = newRow.insertCell(5);

    rowX.innerText = result.x.toString();
    rowY.innerText = result.y.toString();
    rowR.innerText = result.r.toString();
    rowTime.innerText = result.time;
    rowExecTime.innerText = result.execTime;
    rowResult.innerText = result.result;
});

const canvas = document.getElementById('graph');
const ctx = canvas.getContext('2d');

const width = canvas.width;
const height = canvas.height;
const R = 100;
const centerX = width / 2;
const centerY = height / 2;

ctx.fillStyle = '#AAB99A';

ctx.beginPath();
ctx.rect(centerX, centerY, R, R / 2);
ctx.fill();

ctx.beginPath();
ctx.moveTo(centerX, centerY);
ctx.arc(centerX, centerY, R / 2, Math.PI / 2, Math.PI, false);
ctx.lineTo(centerX, centerY);
ctx.fill();

ctx.beginPath();
ctx.moveTo(centerX, centerY);
ctx.lineTo(centerX + R / 2, centerY);
ctx.lineTo(centerX, centerY - R / 2);
ctx.closePath();
ctx.fill();

ctx.beginPath();
ctx.moveTo(centerX, 0);
ctx.lineTo(centerX, height);
ctx.moveTo(0, centerY);
ctx.lineTo(width, centerY);
ctx.strokeStyle = "#6F826A";
ctx.stroke();

ctx.font = "12px monospace";

ctx.strokeText("0", centerX + 6, centerY - 6);
ctx.strokeText("R/2", centerX + R / 2 - 6, centerY - 6);
ctx.strokeText("R", centerX + R - 6, centerY - 6);

ctx.strokeText("-R/2", centerX - R / 2 - 18, centerY - 6);
ctx.strokeText("-R", centerX - R - 6, centerY - 6);

ctx.strokeText("R/2", centerX + 6, centerY - R / 2 + 6);
ctx.strokeText("R", centerX + 6, centerY - R + 6);

ctx.strokeText("-R/2", centerX + 6, centerY + R / 2 + 6);
ctx.strokeText("-R", centerX + 6, centerY + R + 6);