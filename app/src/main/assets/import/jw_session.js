/** 仅在教务主框架检查会话；不回传账号或 Cookie。导航标识只用于拒绝迟到结果。 */
(function (navigationId) {
    if (location.origin !== 'https://byyt.ustb.edu.cn' || window.__beikeSessionCheck === navigationId) return;
    window.__beikeSessionCheck = navigationId;
    function send(fn, args) {
        window.BeikeSession.postMessage(JSON.stringify({ fn: fn, args: args || [], requestId: navigationId }));
    }
    // 登录落地页本身已是失效证据；其 /user/me 可能跨域跳到 SSO，被 fetch 当作网络错误。
    var entries = document.querySelectorAll('a, button, [role="button"], [onclick], .el-button, input[type="button"], input[type="submit"]');
    for (var i = 0; i < entries.length; i++) {
        var label = (entries[i].innerText || entries[i].textContent || entries[i].value || '').replace(/\s/g, '');
        if (label.indexOf('统一身份认证') >= 0 && entries[i].getClientRects().length) {
            send('onAuthRequired');
            return;
        }
    }
    fetch('/user/me', {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: '', signal: AbortSignal.timeout(20000),
    }).then(window.BeikeAuth.read).then(function (text) {
        var me;
        try { me = JSON.parse(text); } catch (_) { throw new Error('检查登录会话失败：响应格式异常'); }
        // /user/me 是裸用户对象；仅验证身份字段存在，不传递或保存其内容。
        var identity = me && (me.yhdm || me.xh);
        if ((typeof identity !== 'string' && typeof identity !== 'number') || !String(identity).trim()) {
            throw new Error('检查登录会话失败：未获取到用户信息，请重试');
        }
        send('onSessionReady');
    }).catch(function (error) {
        send(error.beikeAuthRequired ? 'onAuthRequired' : 'onError',
            [error.beikeAuthRequired ? '' : '检查登录会话失败，请检查网络后重试']);
    });
})('__BEIKE_NAVIGATION_ID__');
