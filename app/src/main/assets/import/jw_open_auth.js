/** 不猜测 SSO URL；支持教务非根路径的登录页，沿用学校生成的参数。 */
(function (navigationId) {
    if (location.origin !== 'https://byyt.ustb.edu.cn' || window.__bkAuthSearchStarted) return;
    window.__bkAuthSearchStarted = true;
    var until = Date.now() + 10000;
    function send(fn) {
        window.BeikeSession.postMessage(JSON.stringify({ fn: fn, args: [], requestId: navigationId }));
    }
    var timer = setInterval(function () {
        var nodes = document.querySelectorAll('a, button, [role="button"], [onclick], .el-button, input[type="button"], input[type="submit"]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var label = (node.innerText || node.textContent || node.value || '').replace(/\s/g, '');
            if (label.indexOf('统一身份认证') < 0 || !node.getClientRects().length || node.disabled) continue;
            clearInterval(timer);
            send('onAuthOpening');
            if (node.tagName === 'A') node.removeAttribute('target');
            node.click();
            return;
        }
        if (Date.now() >= until) {
            clearInterval(timer);
            send('onAuthEntryMissing');
        }
    }, 250);
})('__BEIKE_NAVIGATION_ID__');
