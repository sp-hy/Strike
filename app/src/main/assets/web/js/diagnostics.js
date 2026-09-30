(function () {
    'use strict';

    var API = '/api/diagnostics';
    var TOGGLES = {
        perfLogs: ['Performance logging on', 'Performance logging off', 'Could not change performance logging'],
        openApp: ['Strike will open when the car starts', 'Strike will stay in the background', 'Could not change the startup setting']
    };

    function paint(payload) {
        Object.keys(TOGGLES).forEach(function (id) {
            var toggle = document.getElementById(id);
            toggle.setAttribute('aria-pressed', payload[id] ? 'true' : 'false');
            toggle.disabled = false;
        });
    }

    function load() {
        Strike.core.get(API, paint, function () {});
    }

    Object.keys(TOGGLES).forEach(function (id) {
        var toggle = document.getElementById(id);
        var words = TOGGLES[id];
        toggle.onclick = function () {
            var on = this.getAttribute('aria-pressed') !== 'true';
            toggle.disabled = true;
            Strike.core.post(API, id + '=' + on, function () {
                load();
                Strike.toast(on ? words[0] : words[1]);
            }, function () { load(); Strike.toast(words[2], true); });
        };
    });

    load();
}());
