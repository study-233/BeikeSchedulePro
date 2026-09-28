/** 仅在已登录教务主框架中注入，分页请求复用 WebView 会话。 */
(function (requestId, page) {
    if (window.__beikeNoticesRunning === requestId) return;
    window.__beikeNoticesRunning = requestId;
    function send(fn, args) {
        window.BeikeNOTICES.postMessage(JSON.stringify({ fn: fn, args: args, requestId: requestId }));
    }
    fetch('/component/queryTongZhiGongGaoPage', {
        method: 'POST', credentials: 'same-origin', signal: AbortSignal.timeout(20000),
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
        body: new URLSearchParams({ bt: '', pageNum: page, pageSize: 15, kssj: '' }).toString()
    }).then(window.BeikeAuth.read).then(function (text) {
        send('onNoticesResult', [text]);
    }).catch(function (error) {
        send(error.beikeAuthRequired ? 'onAuthRequired' : 'onError', [String(error)]);
    }).finally(function () {
        if (window.__beikeNoticesRunning === requestId) window.__beikeNoticesRunning = null;
    });
})('__BEIKE_REQUEST_ID__', __BEIKE_PAGE__);
