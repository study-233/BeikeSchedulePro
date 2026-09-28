// 只使用内存中的响应、页面节点和时钟；不请求学校服务。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const asset = name => fs.readFileSync(path.join(__dirname, '../../main/assets/import', name), 'utf8');
const auth = asset('jw_auth.js');
const session = asset('jw_session.js').replaceAll('__BEIKE_NAVIGATION_ID__', '7');
const openAuth = asset('jw_open_auth.js').replaceAll('__BEIKE_NAVIGATION_ID__', '7');
const response = (text, extra = {}) => ({ ok: true, status: 200, text: async () => text, ...extra });

function page(nodes = []) {
    let now = 0;
    let interval;
    const messages = [];
    const context = vm.createContext({
        URL, URLSearchParams, AbortSignal, AbortController, setTimeout, clearTimeout,
        Date: { now: () => now },
        location: { origin: 'https://byyt.ustb.edu.cn', pathname: '/authentication/login' },
        document: { querySelectorAll: () => nodes },
        setInterval: callback => { interval = callback; return 1; },
        clearInterval: () => { interval = undefined; },
        window: { BeikeSession: { postMessage: text => messages.push(JSON.parse(text)) } },
    });
    vm.runInContext(auth, context);
    return { context, messages, tick: time => { now = time; interval?.(); } };
}

test('有效会话仅探测一次 不回传身份信息', { timeout: 2000 }, async () => {
    const { context, messages } = page();
    const calls = [];
    let done;
    const finished = new Promise(resolve => { done = resolve; });
    context.window.BeikeSession.postMessage = text => { messages.push(JSON.parse(text)); done(); };
    context.fetch = async (url, options) => {
        calls.push({ url, options });
        return response(JSON.stringify({ yhdm: 'fixture-only', xm: 'fixture-name' }));
    };
    vm.runInContext(session, context);
    vm.runInContext(session, context);
    await finished;
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/user/me');
    assert.equal(calls[0].options.credentials, 'same-origin');
    assert.equal(calls[0].options.cache, 'no-store');
    assert.equal(calls[0].options.redirect, 'manual');
    assert.deepEqual(messages, [{ fn: 'onSessionReady', args: [], requestId: '7' }]);
});

test('非根路径登录页沿用学校按钮 重复注入和定时回调只点击一次', () => {
    let clicks = 0;
    const node = {
        innerText: '统一 身份认证登录', tagName: 'A', getClientRects: () => [1],
        removeAttribute: name => assert.equal(name, 'target'), click: () => { clicks++; },
    };
    const { context, messages, tick } = page([node]);
    context.fetch = () => { throw new Error('已有登录页面证据，不应请求跨域认证接口'); };
    vm.runInContext(session, context);
    assert.equal(messages[0].fn, 'onAuthRequired');
    vm.runInContext(openAuth, context);
    vm.runInContext(openAuth, context);
    tick(250);
    tick(500);
    assert.equal(clicks, 1);
    assert.equal(messages[1].fn, 'onAuthOpening');
});

test('入口延迟出现可识别 10 秒仍未出现只报告一次兜底', () => {
    const nodes = [];
    const first = page(nodes);
    vm.runInContext(openAuth, first.context);
    first.tick(1000);
    assert.equal(first.messages.length, 0);
    let clicks = 0;
    nodes.push({ value: '统一身份认证', tagName: 'INPUT', getClientRects: () => [1], click: () => { clicks++; } });
    first.tick(2000);
    assert.equal(clicks, 1);
    const missing = page();
    vm.runInContext(openAuth, missing.context);
    missing.tick(9999);
    assert.equal(missing.messages.length, 0);
    missing.tick(10000);
    missing.tick(11000);
    assert.deepEqual(missing.messages.map(it => it.fn), ['onAuthEntryMissing']);
});

test('其他域名不进行会话检查或自动点击', () => {
    const { context, messages, tick } = page();
    context.location.origin = 'https://byyt.ustb.edu.cn.evil.example';
    context.fetch = () => { throw new Error('不应发起请求'); };
    vm.runInContext(session, context);
    vm.runInContext(openAuth, context);
    tick(10000);
    assert.equal(messages.length, 0);
});

test('明确的认证响应才触发失效 403 5xx 和普通 HTML 保持为错误', async () => {
    const { context } = page();
    const read = context.window.BeikeAuth.read;
    for (const reply of [
        response('', { status: 401, ok: false }),
        response('{"code":401}'),
        response('<html>统一身份认证</html>'),
        response('', { redirected: true, url: 'https://sso.ustb.edu.cn/ac-h5/' }),
    ]) {
        await assert.rejects(read(reply), error => error.beikeAuthRequired === true);
    }
    for (const reply of [
        response('<html>用户登录</html>', { status: 503, ok: false }),
        response('', { status: 403, ok: false }),
        response('<html>系统维护</html>'),
    ]) {
        await assert.rejects(read(reply), error => !error.beikeAuthRequired);
    }
    assert.equal(await read(response('{}', {
        redirected: true, url: 'https://sso.ustb.edu.cn.evil.example/ac-h5/',
    })), '{}');
});

test('断网与缺失身份字段不会自动要求登录', { timeout: 2000 }, async () => {
    for (const [reply, reason] of [[new TypeError('Network error'), 'NETWORK'], [response('{}'), 'MISSING_IDENTITY']]) {
        const { context } = page();
        let finish;
        const result = new Promise(resolve => { finish = resolve; });
        context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
        context.fetch = async () => { if (reply instanceof Error) throw reply; return reply; };
        vm.runInContext(session, context);
        assert.deepEqual(await result, { fn: 'onError', args: [reason], requestId: '7' });
    }
});

test('跨域跳转只报告待确认 不误判为断网或直接开始同步', { timeout: 2000 }, async () => {
    const { context } = page();
    let finish;
    const result = new Promise(resolve => { finish = resolve; });
    context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
    context.fetch = async (_, options) => {
        assert.equal(options.redirect, 'manual');
        return { type: 'opaqueredirect', status: 0, ok: false,
            text: () => { throw new Error('不应读取不透明跳转响应'); } };
    };
    vm.runInContext(session, context);
    assert.deepEqual(await result, { fn: 'onError', args: ['REDIRECT'], requestId: '7' });
});

test('探测期间异步出现的学校登录按钮仍能触发认证', { timeout: 2000 }, async () => {
    const nodes = [];
    const { context } = page(nodes);
    let finish;
    const result = new Promise(resolve => { finish = resolve; });
    context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
    context.fetch = async () => {
        nodes.push({ innerText: '统一身份认证', getClientRects: () => [1] });
        throw new TypeError('Failed to fetch');
    };
    vm.runInContext(session, context);
    assert.deepEqual(await result, { fn: 'onAuthRequired', args: [], requestId: '7' });
});

test('会话诊断区分HTTP及格式错误且不泄露响应正文', { timeout: 2000 }, async () => {
    for (const [reply, args] of [
        [response('private-response', { status: 403, ok: false }), ['HTTP', '403']],
        [response('<html>统一身份认证</html>', { status: 503, ok: false }), ['HTTP', '503']],
        [response('private-response'), ['INVALID_JSON']],
        [response('<html>维护页面 fixture-private</html>'), ['UNEXPECTED_HTML']],
    ]) {
        const { context } = page();
        let finish;
        const result = new Promise(resolve => { finish = resolve; });
        context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
        context.fetch = async () => reply;
        vm.runInContext(session, context);
        assert.deepEqual(await result, { fn: 'onError', args, requestId: '7' });
    }
});

test('HTTP服务错误优先于探测期间出现的登录按钮', { timeout: 2000 }, async () => {
    const nodes = [];
    const { context } = page(nodes);
    let finish;
    const result = new Promise(resolve => { finish = resolve; });
    context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
    context.fetch = async () => {
        nodes.push({ innerText: '统一身份认证', getClientRects: () => [1] });
        return response('', { status: 503, ok: false });
    };
    vm.runInContext(session, context);
    assert.deepEqual(await result, { fn: 'onError', args: ['HTTP', '503'], requestId: '7' });
});

test('不依赖AbortSignal timeout且超时会中止请求并清理定时器', { timeout: 2000 }, async () => {
    const { context } = page();
    context.AbortSignal = undefined;
    let timer;
    let cleared = false;
    let requested;
    const started = new Promise(resolve => { requested = resolve; });
    let finish;
    const result = new Promise(resolve => { finish = resolve; });
    context.setTimeout = (callback, milliseconds) => {
        assert.equal(milliseconds, 20000);
        timer = callback;
        return 9;
    };
    context.clearTimeout = id => { assert.equal(id, 9); cleared = true; };
    context.fetch = (_, options) => new Promise((_, reject) => {
        options.signal.addEventListener('abort', () => reject(new Error('aborted')));
        requested();
    });
    context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
    vm.runInContext(session, context);
    await started;
    timer();
    assert.deepEqual(await result, { fn: 'onError', args: ['TIMEOUT'], requestId: '7' });
    // onError 后的 finally 在随后的微任务中执行。
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(cleared, true);
});

test('同步fetch异常也通过原因码返回 不包含原始异常', { timeout: 2000 }, async () => {
    const { context } = page();
    let finish;
    const result = new Promise(resolve => { finish = resolve; });
    context.window.BeikeSession.postMessage = text => finish(JSON.parse(text));
    context.fetch = () => { throw new TypeError('fixture-private-url-and-cookie'); };
    vm.runInContext(session, context);
    assert.deepEqual(await result, { fn: 'onError', args: ['NETWORK'], requestId: '7' });
});

test('课表 成绩和公告脚本均将认证过期作为独立事件回传', { timeout: 2000 }, async () => {
    for (const [script, bridge, task] of [
        ['jw_import.js', 'BeikeImport', 'IMPORT'],
        ['jw_grades.js', 'BeikeGRADES', 'GRADES'],
        ['jw_notices.js', 'BeikeNOTICES', 'NOTICES'],
    ]) {
        const { context } = page();
        let finish;
        const result = new Promise(resolve => { finish = resolve; });
        context.window[bridge] = { postMessage: text => finish(JSON.parse(text)) };
        let calls = 0;
        context.fetch = async () => { calls++; return response('<html>用户登录</html>'); };
        vm.runInContext(asset(script).replaceAll('__BEIKE_REQUEST_ID__', '42')
            .replaceAll('__BEIKE_TASK__', task).replaceAll('__BEIKE_PAGE__', '1'), context);
        assert.equal((await result).fn, 'onAuthRequired');
        // 成绩的 /user/me 失效不能被兜底吞掉后再请求成绩。
        assert.equal(calls, 1);
    }
});
