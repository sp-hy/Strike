(function () {
    'use strict';

    var API = '/api/diagnostics';
    var toggle = document.getElementById('perfLogs');

    function paint(payload) {
        toggle.setAttribute('aria-pressed', payload.perfLogs ? 'true' : 'false');
        toggle.disabled = false;
    }

    function load() {
        Strike.core.get(API, paint, function () {});
    }

    toggle.onclick = function () {
        var on = this.getAttribute('aria-pressed') !== 'true';
        toggle.disabled = true;
        Strike.core.post(API, 'perfLogs=' + on, function () {
            load();
            Strike.toast(on ? 'Performance logging on' : 'Performance logging off');
        }, function () { load(); Strike.toast('Could not change performance logging', true); });
    };

    load();
}());
