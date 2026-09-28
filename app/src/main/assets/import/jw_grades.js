/** 每个任务独立请求和回传；失败不会丢弃其他任务的结果。 */
(function (requestId, task) {
    var key = '__beikeAcademic_' + task;
    if (window[key] === requestId) return;
    window[key] = requestId;
    function send(fn, args) {
        window['Beike' + task].postMessage(JSON.stringify({ fn: fn, args: args, requestId: requestId }));
    }
    function post(url, body, json) {
        return fetch(url, {
            method: 'POST', credentials: 'same-origin', signal: AbortSignal.timeout(20000),
            headers: { 'Content-Type': json ? 'application/json;charset=UTF-8' : 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: json ? JSON.stringify(body) : new URLSearchParams(body || {}).toString()
        }).then(window.BeikeAuth.read).then(function (text) {
            var value;
            try { value = JSON.parse(text); } catch (_) { throw new Error('教务响应格式异常，请重试'); }
            if (value && value.code != null && ![0, 200, '0', '200'].includes(value.code)) {
                throw new Error('教务接口返回异常，请稍后重试');
            }
            return text;
        });
    }
    var result = ['', '', '', '', '', '', '', ''];
    function semester() {
        return post('/component/querydangqianxnxq').then(function (text) {
            result[4] = text;
            var sem = JSON.parse(text);
            if (!sem.XN || !sem.XQ) throw new Error('未获取到当前学期，请重新登录');
            return sem;
        });
    }
    var job;
    if (task === 'GPA') {
        job = post('/cjgl/grcjcx/getgpa').then(function (text) { result[0] = text; });
    } else if (task === 'GRADES') {
        job = post('/user/me').catch(function (error) {
            if (error.beikeAuthRequired) throw error;
            return '{}';
        }).then(function (text) {
            var me = JSON.parse(text);
            return post('/cjgl/grcjcx/grcjcx', {
                xn: null, xq: null, kcmc: null, cxbj: '-1', pylx: me.pylx || '1',
                current: 1, pageSize: 500, xscjlb: null, sffx: null, yhdm: me.yhdm || null
            }, true);
        }).then(function (text) { result[1] = text; });
    } else if (task === 'STUDENT') {
        job = Promise.all([post('/user/me'), post('/UserManager/queryxsxx')]).then(function (texts) {
            result[2] = texts[0]; result[3] = texts[1];
        });
    } else if (task === 'EXAMS') {
        job = semester().then(function (sem) {
            return post('/kscxtj/queryXsksByxhList', { pxn: sem.XN, pxq: sem.XQ, ppylx: '1', pageNum: 1, pageSize: 100 });
        }).then(function (text) { result[5] = text; });
    } else if (task === 'PROGRESS') {
        job = Promise.all([semester(), post('/cjgl/cjzhtjcx/cjcx/getXss')]).then(function (values) {
            var xs = (JSON.parse(values[1]).content || [])[0];
            if (!xs) throw new Error('未获取到培养方案信息');
            var body = { xh: xs.xh, pylx: xs.pylx || '1', nj: xs.nj, jzxnxq: values[0].XN + values[0].XQ, xjid: xs.xjid, fah: xs.fah };
            return Promise.all([
                post('/cjgl/cjzhtjcx/cjcx/queryXflbyq', body, true),
                post('/cjgl/cjzhtjcx/cjcx/queryBxkqk', Object.assign({ sfcxxfj: '0' }, body), true)
            ]);
        }).then(function (texts) { result[6] = texts[0]; result[7] = texts[1]; });
    } else {
        job = Promise.reject(new Error('未知教务任务'));
    }
    job.then(function () { send('onGradesResult', result); })
        .catch(function (error) { send(error.beikeAuthRequired ? 'onAuthRequired' : 'onError', [String(error)]); })
        .finally(function () { if (window[key] === requestId) window[key] = null; });
})('__BEIKE_REQUEST_ID__', '__BEIKE_TASK__');
