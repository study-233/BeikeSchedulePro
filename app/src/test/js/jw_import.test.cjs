// 使用 Node 内置测试模块；只模拟教务响应，不访问学校服务。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '../../main/assets/import/jw_auth.js'), 'utf8') + '\n' +
    fs.readFileSync(path.join(__dirname, '../../main/assets/import/jw_import.js'), 'utf8');
const fixture = JSON.parse(fs.readFileSync(
    path.join(__dirname, '../resources/queryMonthList-2026-2027-1-excerpt.json'), 'utf8').replace(/^\uFEFF/, ''));

async function importCalendar(xiaoli) {
    const calls = [];
    let receive;
    const result = new Promise(resolve => { receive = resolve; });
    const replies = {
        '/component/querydangqianxnxq': { XN: '2026-2027', XQ: '1' },
        '/xszykb/querykbsffb': '1',
        '/xszykb/queryxszykbzong': { content: [] },
        '/component/queryKbjg': { content: [] },
        '/component/queryzclist': { content: [1, 2, 3, 4, 99].map(ZC => ({ ZC })) },
    };
    const context = {
        URL, URLSearchParams,
        AbortSignal,
        window: { BeikeImport: { postMessage: message => receive(JSON.parse(message)) } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            let reply;
            if (url === '/Xiaoli/queryMonthList') {
                if (xiaoli instanceof Error) throw xiaoli;
                reply = xiaoli;
            } else if (url === '/component/queryRlZcSj') {
                const week = Number(new URLSearchParams(options.body).get('djz'));
                const dates = ['2026-09-07', '2026-09-14', '2026-09-21', '2026-10-05'];
                reply = { content: [{ xqj: '1', rq: dates[week - 1] }] };
            } else {
                assert.ok(Object.hasOwn(replies, url), `Unexpected request: ${url}`);
                reply = replies[url];
            }
            return { ok: true, text: async () => JSON.stringify(reply) };
        },
    };
    vm.runInNewContext(script, context);
    const message = await result;
    assert.equal(message.fn, 'onResult');
    return { calendar: JSON.parse(message.args[5]), calls };
}

test('真实校历节选保留教学周内假期和周末，教学周仍跳过国庆周', { timeout: 2000 }, async () => {
    const { calendar, calls } = await importCalendar(fixture);
    assert.deepEqual(calendar.weeks, [
        { zc: 1, monday: '2026-09-07' }, { zc: 2, monday: '2026-09-14' },
        { zc: 3, monday: '2026-09-21' }, { zc: 4, monday: '2026-10-05' },
    ]);
    for (const date of ['2026-09-25', '2026-09-26', '2026-09-27', '2026-10-05', '2026-10-06', '2026-10-07']) {
        assert.ok(calendar.holidayDates.includes(date), date);
    }
    for (const date of ['2026-09-24', '2026-10-08', '2026-10-09']) {
        assert.ok(!calendar.holidayDates.includes(date), date);
    }
    const request = calls.find(call => call.url === '/Xiaoli/queryMonthList');
    assert.equal(request.options.headers.RoleCode, '01');
    assert.equal(request.options.body, 'xn=2026-2027&xq=1');
});

test('逐日标记按星期配对并隔离学期，不因周末或 ZC=99 自行标色', { timeout: 2000 }, async () => {
    const { calendar } = await importCalendar({ xlList: [
        { XNXQ: '2026-20271', ZC: 1, MON: '2026-09-07', MON1: '0' },
        { XNXQ: '2026-20271', ZC: 1, SAT: '2026-09-12', SAT1: '0', MON1: '1' },
        { XNXQ: '2026-20271', ZC: 99, MON: '2026-09-28', MON1: null },
        { XNXQ: '2026-20272', ZC: 1, MON: '2027-02-01', MON1: '1' },
        { XNXQ: '2026-20271', ZC: 1, SUN: '2026-09-13', SUN1: 1 },
        { XNXQ: '2026-20271', ZC: 1, SUN: '2026-09-13', SUN1: '1' },
    ] });
    assert.deepEqual(calendar.holidayDates, ['2026-09-13']);
    assert.deepEqual(calendar.weeks, [{ zc: 1, monday: '2026-09-07' }]);
});

test('校历失败时逐周兜底仍跳周，但不生成放假日期', { timeout: 2000 }, async () => {
    const { calendar } = await importCalendar(new Error('模拟校历不可用'));
    assert.equal(calendar.weeks[3].monday, '2026-10-05');
    assert.equal(calendar.weeks.length, 4);
    assert.deepEqual(calendar.holidayDates, []);
});
