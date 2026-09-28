/** 同源响应分类；普通 HTML 错误页、403 和网络错误不能当作登录失效。 */
(function () {
    function expired() {
        var error = new Error('会话已过期，请重新登录后重试');
        error.beikeAuthRequired = true;
        return error;
    }
    function loginUrl(value) {
        try {
            var url = new URL(value);
            return url.protocol === 'https:' && (
                url.hostname === 'sso.ustb.edu.cn' || url.hostname === 'sis.ustb.edu.cn' ||
                (url.hostname === 'byyt.ustb.edu.cn' && /\/(?:login|authentication\/login)\/?$/.test(url.pathname)));
        } catch (_) { return false; }
    }
    function loginHtml(text) {
        return /^\s*</.test(text) && /统一身份认证|用户登录|type\s*=\s*["']password["']|\blogin\b/i.test(text);
    }
    window.BeikeAuth = {
        read: function (response) {
            if (response.status === 401) return Promise.reject(expired());
            if (!response.ok) return Promise.reject(new Error('教务请求失败（HTTP ' + response.status + '）'));
            if (response.redirected && loginUrl(response.url)) return Promise.reject(expired());
            return response.text().then(function (text) {
                if (loginHtml(text)) throw expired();
                if (/^\s*</.test(text)) throw new Error('教务响应格式异常，请稍后重试');
                var value;
                try { value = JSON.parse(text); } catch (_) { return text; }
                if (value && (value.code === 401 || value.code === '401')) throw expired();
                return text;
            });
        },
    };
})();
