/** 仅在教务主框架检查会话；不回传账号或 Cookie。导航标识只用于拒绝迟到结果。 */
(function (navigationId) {
    if (location.origin !== 'https://byyt.ustb.edu.cn' || window.__beikeSessionCheck === navigationId) return;
    window.__beikeSessionCheck = navigationId;
    function send(fn, args) {
        window.BeikeSession.postMessage(JSON.stringify({ fn: fn, args: args || [], requestId: navigationId }));
    }
    function hasLoginEntry() {
        var entries = document.querySelectorAll('a, button, [role="button"], [onclick], .el-button, input[type="button"], input[type="submit"]');
        for (var i = 0; i < entries.length; i++) {
            var label = (entries[i].innerText || entries[i].textContent || entries[i].value || '').replace(/\s/g, '');
            if (label.indexOf('统一身份认证') >= 0 && entries[i].getClientRects().length && !entries[i].disabled) return true;
        }
        return false;
    }
    function failure(reason) {
        var error = new Error('会话检查未完成');
        error.beikeSessionReason = reason;
        return error;
    }
    // 页面框架加载完成不代表异步登录按钮已经出现；失败时还会再检查一次。
    if (hasLoginEntry()) { send('onAuthRequired'); return; }
    var timeoutId;
    var timedOut = false;
    Promise.resolve().then(function () {
        // 不依赖较新的 AbortSignal.timeout；同步异常也必须经桥返回，而不是静默等待宿主超时。
        var controller = new AbortController();
        timeoutId = setTimeout(function () { timedOut = true; controller.abort(); }, 20000);
        return fetch('/user/me', {
            method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'manual',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: '', signal: controller.signal,
        });
    }).then(function (response) {
        // 不让会话探测跟随跨域 SSO 而退化为不透明的 CORS 网络错误。
        // 重定向本身不证明登录过期，交给宿主展示学校页面确认，仍不能开始同步。
        if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
            throw failure('REDIRECT');
        }
        return window.BeikeAuth.read(response);
    }).then(function (text) {
        var me;
        try { me = JSON.parse(text); } catch (_) { throw failure('INVALID_JSON'); }
        // /user/me 是裸用户对象；仅验证身份字段存在，不传递或保存其内容。
        var identity = me && (me.yhdm || me.xh);
        if ((typeof identity !== 'string' && typeof identity !== 'number') || !String(identity).trim()) {
            throw failure('MISSING_IDENTITY');
        }
        send('onSessionReady');
    }).catch(function (error) {
        if (error && error.beikeAuthRequired) { send('onAuthRequired'); return; }
        // 403/5xx 即使页面含登录文字也仍是服务错误，不能误报登录失效。
        if (error && error.beikeHttpStatus) {
            send('onError', ['HTTP', String(error.beikeHttpStatus)]);
            return;
        }
        if (hasLoginEntry()) { send('onAuthRequired'); return; }
        // 仅传固定原因码，不传服务器响应、个人信息、带认证参数的 URL 或原始异常内容。
        var reason = timedOut ? 'TIMEOUT' : error && error.beikeSessionReason;
        if (!reason) reason = error && error.name === 'TypeError' ? 'NETWORK' : 'CHECK_FAILED';
        send('onError', [reason]);
    }).finally(function () {
        if (timeoutId !== undefined) clearTimeout(timeoutId);
    });
})('__BEIKE_NAVIGATION_ID__');
