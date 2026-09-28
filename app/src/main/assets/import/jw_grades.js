/**
 * 北科教务成绩+考试+学业进度抓取脚本。在已登录的 byyt 页面注入，同源 fetch 复用 SESSION。
 * grcjcx 为 JSON POST（与课表接口的 form 提交不同），getgpa/getXss 为 form POST，
 * queryXflbyq/queryBxkqk 为 JSON POST（参数依赖 getXss 的培养方案标识），
 * queryXsksByxhList 参数带 p 前缀（pxn/pxq/ppylx）。
 * 结果经 BeikeGrades 桥回传：send('onGradesResult', [gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk])。
 */
(function (requestId) {
    if (window.__beikeGradesRunning === requestId) return;
    window.__beikeGradesRunning = requestId;

    /**
     * 回传桥消息。桥由平台按 origin 限定（WebViewCompat.addWebMessageListener，
     * 只注入给 ustb.edu.cn 的页面），统一用 JSON 信封 {fn, args} 走 postMessage。
     */
    function send(fn, args) {
        try {
            window.BeikeGrades.postMessage(JSON.stringify({ fn: fn, args: args, requestId: requestId }));
        } catch (e) { /* 桥不可用 */ }
    }

    /**
     * JSON.parse 的统一入口：会话过期时教务会 302 到登录页并返回 200 的 HTML，
     * 直接 JSON.parse 会把英文语法错误铺到中文界面。
     */
    function parseJson(text, hint) {
        if (typeof text !== 'string' || text.trim().charAt(0) === '<') {
            throw new Error('会话已过期，请重新登录教务系统后重试');
        }
        try {
            return JSON.parse(text);
        } catch (e) {
            throw new Error((hint || '教务响应格式异常') + '，请重新登录后重试');
        }
    }

    /** 共用的 fetch 选项：连接挂起时 fetch 可以永不 resolve，界面会一直停在"抓取中…"。 */
    function fetchOpts(extra) {
        return Object.assign({ credentials: 'same-origin', signal: AbortSignal.timeout(20000) }, extra);
    }

    function postForm(url, params) {
        return fetch(url, fetchOpts({
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: new URLSearchParams(params).toString()
        })).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status); // 会话过期/5xx 时 text 是 HTML，显式报状态更可读
            return r.text();
        });
    }

    function postJson(url, body) {
        return fetch(url, fetchOpts({
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(body)
        })).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        });
    }

    /** 学业进度：getXss 取培养方案标识 → 并发查学分类别要求 + 毕业总进度；失败回退空串不阻塞成绩。 */
    function fetchProgress(xn, xq) {
        return postForm('/cjgl/cjzhtjcx/cjcx/getXss', {}).then(function (text) {
            var xs = (parseJson(text, '培养方案标识解析失败').content || [])[0] || {};
            var body = {
                xh: xs.xh || '', pylx: xs.pylx || '1', nj: xs.nj || '',
                jzxnxq: xn + xq, xjid: xs.xjid || '', fah: xs.fah || ''
            };
            var bxkqkBody = Object.assign({ sfcxxfj: '0' }, body);
            return Promise.all([
                postJson('/cjgl/cjzhtjcx/cjcx/queryXflbyq', body),
                postJson('/cjgl/cjzhtjcx/cjcx/queryBxkqk', bxkqkBody)
            ]);
        }).catch(function () { return ['', '']; });
    }

    Promise.all([
        postForm('/component/querydangqianxnxq', {}),
        // /user/me 只用来取 pylx/yhdm 两个**可有可无**的参数（下面都有 || 兜底），
        // 它失败不该让整次抓取失败（此前会打进外层 catch，用户看到"抓取失败：HTTP 5xx"）
        postForm('/user/me', {}).catch(function () { return ''; })
    ]).then(function (rs) {
        var sem = parseJson(rs[0], '当前学期解析失败');
        if (!sem || !sem.XN) throw new Error('未获取到当前学期，请确认已登录');
        var me = {};
        try { me = JSON.parse(rs[1]); } catch (e) { /* 匿名兜底 */ }
        return Promise.all([
            postForm('/cjgl/grcjcx/getgpa', {}),
            postJson('/cjgl/grcjcx/grcjcx', {
                xn: null, xq: null, kcmc: null, cxbj: '-1',
                pylx: me.pylx || '1',
                current: 1, pageSize: 500,
                xscjlb: null, sffx: null, yhdm: me.yhdm || null
            }),
            postForm('/UserManager/queryxsxx', {}).catch(function () { return ''; }),
            postForm('/kscxtj/queryXsksByxhList', {
                pxn: sem.XN, pxq: sem.XQ, ppylx: '1', pageNum: 1, pageSize: 100
            }).catch(function () { return ''; }),
            fetchProgress(sem.XN, sem.XQ)
        ]).then(function (all) {
            if (window.__beikeGradesRunning === requestId) window.__beikeGradesRunning = null;
            // all[0]=getgpa, all[1]=grcjcx, all[2]=queryxsxx, all[3]=考试, all[4]=[xflbyq, bxkqk]
            send('onGradesResult', [all[0], all[1], rs[1], all[2], rs[0], all[3], all[4][0], all[4][1]]);
        });
    }).catch(function (e) {
        if (window.__beikeGradesRunning === requestId) window.__beikeGradesRunning = null;
        send('onError', [String(e)]);
    });
})('__BEIKE_REQUEST_ID__');
