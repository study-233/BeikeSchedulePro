/**
 * 北科本研一体化教务系统（byyt.ustb.edu.cn）课表抓取脚本。
 * 在已登录的 /authentication/main 页面注入，同源 fetch 复用 SESSION。
 * 接口定义见 docs/TECH_DESIGN.md 2.1 节。
 *
 * 教学周日历（修国庆跳周）：优先 Xiaoli/queryMonthList 一次取全量校历
 * （需 RoleCode 头；xlList 按周 7 条、每天一条，MON/TUES/... 字段只有一个非空），
 * 失败则逐周 queryRlZcSj 兜底。产出统一结构：
 *   {"totalWeeks":18, "weeks":[{"zc":1,"monday":"2026-09-07"}, ...], "holidayDates":["2026-09-25", ...]}
 */
(function (requestId) {
    if (window.__beikeRunning === requestId) return;
    window.__beikeRunning = requestId;

    /**
     * 回传桥消息。桥由平台按 origin 限定（WebViewCompat.addWebMessageListener，
     * 只注入给 ustb.edu.cn 的页面），统一用 JSON 信封 {fn, args} 走 postMessage。
     * 桥不可用（非教务页面）时静默：手动抓取在登录页触发属于用户误操作，
     * 界面侧靠"页面加载即复位抓取态"给出重试入口。
     */
    function send(fn, args) {
        try {
            window.BeikeImport.postMessage(JSON.stringify({ fn: fn, args: args, requestId: requestId }));
        } catch (e) { /* 桥不可用 */ }
    }

    function post(url, params, headers) {
        return fetch(url, {
            method: 'POST',
            headers: Object.assign({
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
            }, headers || {}),
            body: new URLSearchParams(params).toString(),
            credentials: 'same-origin',
            // 超时兜底：连接挂起时 fetch 可以永不 resolve，界面会一直停在"抓取中…"。
            // 单次请求 20 秒足够（兜底路径最多 25 次顺序请求，各算各的）。
            signal: AbortSignal.timeout(20000)
        }).then(window.BeikeAuth.read);
    }

    /**
     * JSON.parse 的统一入口：会话过期时教务会 302 到登录页并返回 200 的 HTML，
     * 直接 JSON.parse 会把英文语法错误（Unexpected token '<'）铺到中文界面。
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

    /** 校历接口 → 统一周历结构；失败返回 null（由调用方兜底）。 */
    function calendarFromXiaoli(xn, xq) {
        return post('/Xiaoli/queryMonthList', { xn: xn, xq: xq }, { RoleCode: '01' })
            .then(function (text) {
                var data = parseJson(text, '校历解析失败');
                var semKey = xn + xq; // xlList 里 XNXQ 形如 "2026-20271"
                var rows = (data.xlList || []).filter(function (e) { return e.XNXQ === semKey; });
                var weeks = rows
                    .filter(function (e) { return e.MON && e.ZC >= 1 && e.ZC <= 90; })
                    .map(function (e) { return { zc: e.ZC, monday: e.MON }; })
                    .sort(function (a, b) { return a.zc - b.zc; });
                if (!weeks.length) return null;
                // 星期日期字段对应的 *1 为学校逐日放假标记（与月历 sffj 一致）。
                // 教学周内也会放假，不能用 ZC=99 或自行推算周末代替。
                var holidayDates = [];
                rows.forEach(function (e) {
                    ['MON', 'TUES', 'WED', 'THUR', 'FRI', 'SAT', 'SUN'].forEach(function (day) {
                        if (e[day] && String(e[day + '1']) === '1') holidayDates.push(e[day]);
                    });
                });
                return { weeks: weeks, holidayDates: Array.from(new Set(holidayDates)).sort() };
            })
            .catch(function (error) {
                if (error.beikeAuthRequired) throw error;
                return null;
            });
    }

    /** 逐周 queryRlZcSj 兜底 → 统一周历结构。 */
    function calendarByWeekLoop(xn, xq, zcList) {
        var weeks = [];
        return zcList.reduce(function (chain, zc) {
            return chain.then(function () {
                return post('/component/queryRlZcSj', { xn: xn, xq: xq, djz: String(zc) })
                    .then(function (text) {
                        var content = (parseJson(text, '校历周次解析失败') || {}).content || [];
                        var mon = content.filter(function (e) { return e.xqj === '1'; })[0];
                        if (mon && mon.rq) weeks.push({ zc: zc, monday: mon.rq });
                    });
            });
        }, Promise.resolve()).then(function () {
            return weeks.length ? weeks : null;
        });
    }

    post('/component/querydangqianxnxq', {})
        .then(function (semText) {
            var sem = parseJson(semText, '当前学期解析失败');
            if (!sem || !sem.XN) throw new Error('未获取到当前学期，请确认已登录');
            return Promise.all([
                post('/xszykb/querykbsffb', { xn: sem.XN, xq: sem.XQ }),
                post('/xszykb/queryxszykbzong', { xn: sem.XN, xq: sem.XQ }),
                post('/component/queryKbjg', { xn: sem.XN, xq: sem.XQ, pylx: '1' }),
                post('/component/queryRlZcSj', { xn: sem.XN, xq: sem.XQ, djz: '1' }),
                post('/component/queryzclist', { xn: sem.XN, xq: sem.XQ })
            ]).then(function (rs) {
                var zcList = [];
                try {
                    // queryzclist 返回**包装**结构 {content:[{ZC}]}（见 docs/JWXT_API.md），
                    // 此前按裸数组解析 → `.map is not a function` 抛异常 → 被下面的 catch
                    // 静默吞掉 → zcList 恒为空。后果是兜底路径退化成硬编码的 25 周顺序请求，
                    // 且 totalWeeks 只能取校历长度。两种形态都兼容。
                    var raw = parseJson(rs[4], '周次列表解析失败');
                    var arr = Array.isArray(raw) ? raw : ((raw && raw.content) || []);
                    zcList = arr
                        .map(function (e) { return e && e.ZC; })
                        .filter(function (z) { return typeof z === 'number' && z >= 1 && z <= 90; });
                } catch (e) { /* 周次列表异常时由校历自行推断 */ }

                return calendarFromXiaoli(sem.XN, sem.XQ).then(function (calendar) {
                    if (calendar) return calendar;
                    var loopList = zcList.length ? zcList
                        : Array.from({ length: 25 }, function (_, i) { return i + 1; });
                    return calendarByWeekLoop(sem.XN, sem.XQ, loopList).then(function (weeks) {
                        // 逐周接口没有逐日放假标记；无可靠数据时不标色。
                        return { weeks: weeks || [], holidayDates: [] };
                    });
                }).then(function (result) {
                    var weeks = result.weeks;
                    var totalWeeks = Math.max(
                        zcList.length ? Math.max.apply(null, zcList) : 0,
                        weeks ? weeks.length : 0,
                        16
                    );
                    var calendar = JSON.stringify({ totalWeeks: totalWeeks, weeks: weeks || [], holidayDates: result.holidayDates });
                    // 成功路径也必须复位重入标志：否则"手动抓取"按钮在首次成功后
                    // 变成静默无操作的空按钮（jw_grades.js 一直在成功路径复位，此处是漏改）。
                    if (window.__beikeRunning === requestId) window.__beikeRunning = null;
                    send('onResult', [semText, rs[0], rs[1], rs[2], rs[3], calendar]);
                });
            });
        })
        .catch(function (e) {
            if (window.__beikeRunning === requestId) window.__beikeRunning = null;
            send(e.beikeAuthRequired ? 'onAuthRequired' : 'onError', [String(e)]);
        });
})('__BEIKE_REQUEST_ID__');
