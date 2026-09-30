(function () {
    'use strict';

    var API = '/api/power';
    var CUTOFF = 'power.cutoffVolts';
    var SWITCHES = {
        'power.keepAwake': ['powerKeepAwake', 'The car will stay awake when off', 'The car will sleep unless surveillance or Online need it', 'Could not change keep awake'],
        'power.cameraHeartbeat': ['powerCamera', 'Camera heartbeat on', 'Camera heartbeat off', 'Could not change the camera heartbeat'],
        'power.shutdownHold': ['powerShutdown', 'Shutdown hold on', 'Shutdown hold off', 'Could not change the shutdown hold'],
        'power.network': ['powerNetwork', 'Wi-Fi and mobile data will be kept on while parked', 'Wi-Fi and mobile data are left to the car', 'Could not change the network setting'],
        'power.cloudHeartbeat': ['powerCloud', 'BYD cloud heartbeat on', 'BYD cloud heartbeat off', 'Could not change the cloud heartbeat']
    };
    var CLOUD_API = '/api/power/cloud';
    var cutoff = document.getElementById('powerCutoff');
    var steps = cutoff.querySelectorAll('.seg__btn');
    var modal = document.getElementById('cloud');
    var openCloud = document.getElementById('cloudOpen');
    var signOut = document.getElementById('cloudSignOut');
    var signIn = document.getElementById('cloudSignIn');
    var email = document.getElementById('cloudEmail');
    var password = document.getElementById('cloudPassword');
    var country = document.getElementById('cloudCountry');
    var signedIn = false;

    function seconds(age) {
        return age < 60 ? age + ' s' : Math.round(age / 60) + ' min';
    }

    function cloudState(cloud) {
        if (!cloud.account) return 'Not signed in';
        var who = cloud.account + ', car ' + cloud.vin;
        var beat = cloud.heartbeat;
        if (!beat) return who;
        if (beat.problem) return who + '. ' + beat.problem;
        if (beat.active && typeof beat.lastOkAgeS === 'number') return who + '. Reached the car ' + seconds(beat.lastOkAgeS) + ' ago';
        if (beat.active) return who + '. Starting';
        return who;
    }

    function paintCloud(cloud, on) {
        signedIn = !!cloud.account;
        document.getElementById('cloudState').textContent = cloudState(cloud);
        document.getElementById('powerCloud').disabled = !signedIn && !on;
        openCloud.textContent = signedIn ? 'Change account' : 'Sign in';
        openCloud.disabled = false;
        signOut.hidden = !signedIn;
        if (!country.options.length) {
            cloud.countries.forEach(function (entry) {
                var option = document.createElement('option');
                option.value = entry.code;
                option.textContent = entry.name;
                country.appendChild(option);
            });
            country.value = cloud.country;
        }
    }

    function state(payload) {
        if (payload.lowBattery) return '12 V battery is low. Parked power is released until the car starts.';
        if (typeof payload.volts !== 'number') return '12 V battery reading unavailable';
        return '12 V battery ' + payload.volts.toFixed(1) + ' V';
    }

    function paint(payload) {
        var values = payload.values;
        Object.keys(SWITCHES).forEach(function (key) {
            var toggle = document.getElementById(SWITCHES[key][0]);
            toggle.setAttribute('aria-pressed', values[key] ? 'true' : 'false');
            toggle.disabled = false;
        });
        for (var i = 0; i < steps.length; i++) {
            steps[i].setAttribute('aria-pressed', steps[i].getAttribute('data-value') === values[CUTOFF] ? 'true' : 'false');
            steps[i].disabled = false;
        }
        document.getElementById('powerState').textContent = state(payload);
        paintCloud(payload.cloud, values['power.cloudHeartbeat']);
    }

    function hide() {
        modal.hidden = true;
        password.value = '';
        openCloud.focus();
    }

    openCloud.onclick = function () {
        modal.hidden = false;
        email.focus();
    };
    document.getElementById('cloudClose').onclick = hide;
    modal.onclick = function (event) { if (event.target === modal) hide(); };
    modal.onkeydown = function (event) { if (event.key === 'Escape') hide(); };

    signIn.onclick = function () {
        var xhr = new XMLHttpRequest();
        signIn.disabled = true;
        signIn.textContent = 'Checking with BYD';
        xhr.open('POST', CLOUD_API, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 60000;
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) return;
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            signIn.disabled = false;
            signIn.textContent = 'Sign in';
            if (xhr.status === 200) {
                hide();
                load();
                Strike.toast('Signed in to BYD');
            } else {
                Strike.toast(xhr.responseText || 'Could not reach BYD', true);
            }
        };
        xhr.send('email=' + encodeURIComponent(email.value) + '&password=' + encodeURIComponent(password.value) +
            '&country=' + encodeURIComponent(country.value));
    };

    signOut.onclick = function () {
        signOut.disabled = true;
        Strike.core.del(CLOUD_API, function () {
            signOut.disabled = false;
            load();
            Strike.toast('Signed out of BYD');
        }, function () { signOut.disabled = false; Strike.toast('Could not sign out', true); });
    };

    function load() {
        Strike.core.get(API, paint, function () {});
    }

    function save(key, value, done, failed) {
        Strike.core.post(API, 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value), function () {
            load();
            Strike.toast(done);
        }, function () { load(); Strike.toast(failed, true); });
    }

    Object.keys(SWITCHES).forEach(function (key) {
        var words = SWITCHES[key];
        var toggle = document.getElementById(words[0]);
        toggle.onclick = function () {
            var on = this.getAttribute('aria-pressed') !== 'true';
            toggle.disabled = true;
            save(key, on ? 'true' : 'false', on ? words[1] : words[2], words[3]);
        };
    });

    for (var i = 0; i < steps.length; i++) {
        steps[i].onclick = function () {
            var value = this.getAttribute('data-value');
            save(CUTOFF, value, value === 'off' ? 'Low battery cutoff off' : 'Parked power stops below ' + value + ' V',
                'Could not change the cutoff');
        };
    }

    load();
    setInterval(load, 15000);
}());
