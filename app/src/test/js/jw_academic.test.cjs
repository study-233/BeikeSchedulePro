// 模拟同源 fetch 与消息桥，不访问学校服务。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function runTask(task, replies, page = 1) {
    const asset = task === 'NOTICES' ? 'jw_notices.js' : 'jw_grades.js';
    const source = fs.readFileSync(path.join(__dirname, '../../main/assets/import/jw_auth.js'), 'utf8') + '\n' +
        fs.readFileSync(path.join(__dirname, '../../main/assets/import', asset), 'utf8')
        .replaceAll('__BEIKE_REQUEST_ID__', '42').replaceAll('__BEIKE_TASK__', task).replaceAll('__BEIKE_PAGE__', String(page));
    const calls = [];
    let receive;
    const result = new Promise(resolve => { receive = resolve; });
    const context = {
        URL, URLSearchParams, AbortSignal,
        window: { ['Beike' + task]: { postMessage: message => receive(JSON.parse(message)) } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            assert.ok(Object.hasOwn(replies, url), `Unexpected request: ${url}`);
            const reply = replies[url];
            if (reply instanceof Error) throw reply;
            return { ok: true, text: async () => typeof reply === 'string' ? reply : JSON.stringify(reply) };
        },
    };
    vm.runInNewContext(source, context);
    return result.then(message => ({ message, calls }));
}

test('公告分页使用用户提供表单及同源会话', { timeout: 2000 }, async () => {
    const { message, calls } = await runTask('NOTICES', {
        '/component/queryTongZhiGongGaoPage': { list: [], pageNum: 2, hasNextPage: false, nextPage: 0, total: 15 },
    }, 2);
    assert.equal(message.fn, 'onNoticesResult');
    assert.equal(message.requestId, '42');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].options.body, 'bt=&pageNum=2&pageSize=15&kssj=');
    assert.equal(calls[0].options.credentials, 'same-origin');
});

test('公告登录HTML不能回传为成功列表', { timeout: 2000 }, async () => {
    const { message } = await runTask('NOTICES', { '/component/queryTongZhiGongGaoPage': '<html>login</html>' });
    assert.equal(message.fn, 'onAuthRequired');
    assert.match(message.args[0], /会话已过期/);
});

test('GPA失败不阻止考试任务成功且重试只请求GPA', { timeout: 2000 }, async () => {
    const [{ message: failed, calls }, { message: exams }] = await Promise.all([
        runTask('GPA', { '/cjgl/grcjcx/getgpa': new Error('HTTP 503') }),
        runTask('EXAMS', {
            '/component/querydangqianxnxq': { XN: '2026-2027', XQ: '1' },
            '/kscxtj/queryXsksByxhList': { list: [], total: 0 },
        }),
    ]);
    assert.equal(failed.fn, 'onError');
    assert.equal(calls.length, 1);
    assert.equal(exams.fn, 'onGradesResult');
    assert.deepEqual(JSON.parse(exams.args[5]), { list: [], total: 0 });
    const retry = await runTask('GPA', { '/cjgl/grcjcx/getgpa': { BL: 4.0 } });
    assert.equal(retry.calls.length, 1);
    assert.equal(retry.message.fn, 'onGradesResult');
});

test('成绩任务失败不会发出成功结果', { timeout: 2000 }, async () => {
    const { message } = await runTask('GRADES', {
        '/user/me': { pylx: '1', yhdm: 'test' },
        '/cjgl/grcjcx/grcjcx': { code: 500, content: null },
    });
    assert.equal(message.fn, 'onError');
});
