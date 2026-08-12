/**
 * Lightweight Markdown Renderer for JCEF Chat UI
 * Handles: code blocks, inline code, headers, bold, italic, strikethrough,
 *          links, images, blockquotes, lists, horizontal rules, tables
 */
var MarkdownRenderer = (function () {

    function escapeHtml(text) {
        if (!text) return '';
        return text
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function render(src) {
        if (!src) return '';
        return parseBlocks(src);
    }

    /* ---- block-level parser ---- */
    function parseBlocks(text) {
        var lines = text.split('\n');
        var html = [];
        var i = 0;

        while (i < lines.length) {
            var line = lines[i];

            // fenced code block
            if (/^```/.test(line)) {
                var lang = line.slice(3).trim();
                var code = [];
                i++;
                while (i < lines.length && !/^```/.test(lines[i])) {
                    code.push(lines[i]);
                    i++;
                }
                if (i < lines.length) i++; // skip closing ```
                var cls = lang ? ' class="language-' + escapeHtml(lang) + '"' : '';
                html.push('<pre><code' + cls + '>' + escapeHtml(code.join('\n')) + '</code></pre>');
                continue;
            }

            // header
            var hm = line.match(/^(#{1,6})\s+(.+)/);
            if (hm) {
                var lvl = hm[1].length;
                html.push('<h' + lvl + '>' + processInline(hm[2]) + '</h' + lvl + '>');
                i++;
                continue;
            }

            // horizontal rule
            if (/^(\*{3,}|-{3,}|_{3,})\s*$/.test(line)) {
                html.push('<hr>');
                i++;
                continue;
            }

            // blockquote
            if (/^>\s?/.test(line)) {
                var q = [];
                while (i < lines.length && /^>\s?/.test(lines[i])) {
                    q.push(lines[i].replace(/^>\s?/, ''));
                    i++;
                }
                html.push('<blockquote>' + parseBlocks(q.join('\n')) + '</blockquote>');
                continue;
            }

            // unordered list
            if (/^[\-\*\+]\s/.test(line)) {
                var items = [];
                while (i < lines.length && /^[\-\*\+]\s/.test(lines[i])) {
                    items.push(lines[i].replace(/^[\-\*\+]\s/, ''));
                    i++;
                }
                html.push('<ul>' + items.map(function (t) {
                    return '<li>' + processInline(t) + '</li>';
                }).join('') + '</ul>');
                continue;
            }

            // ordered list
            if (/^\d+\.\s/.test(line)) {
                var oitems = [];
                while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
                    oitems.push(lines[i].replace(/^\d+\.\s/, ''));
                    i++;
                }
                html.push('<ol>' + oitems.map(function (t) {
                    return '<li>' + processInline(t) + '</li>';
                }).join('') + '</ol>');
                continue;
            }

            // table (GFM)
            if (line.indexOf('|') !== -1 && i + 1 < lines.length &&
                /^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(lines[i + 1])) {
                var hdrCells = parseTR(line);
                i += 2; // skip header + separator
                var rows = [];
                while (i < lines.length && lines[i].indexOf('|') !== -1 && lines[i].trim() !== '') {
                    rows.push(parseTR(lines[i]));
                    i++;
                }
                var th = '<thead><tr>' + hdrCells.map(function (c) {
                    return '<th>' + processInline(c) + '</th>';
                }).join('') + '</tr></thead>';
                var tb = '<tbody>' + rows.map(function (r) {
                    return '<tr>' + r.map(function (c) {
                        return '<td>' + processInline(c) + '</td>';
                    }).join('') + '</tr>';
                }).join('') + '</tbody>';
                html.push('<table>' + th + tb + '</table>');
                continue;
            }

            // blank line
            if (line.trim() === '') { i++; continue; }

            // paragraph — collect consecutive non-special lines
            var p = [];
            while (i < lines.length &&
                lines[i].trim() !== '' &&
                !/^```/.test(lines[i]) &&
                !/^#{1,6}\s/.test(lines[i]) &&
                !/^(\*{3,}|-{3,}|_{3,})\s*$/.test(lines[i]) &&
                !/^>\s?/.test(lines[i]) &&
                !/^[\-\*\+]\s/.test(lines[i]) &&
                !/^\d+\.\s/.test(lines[i])) {
                p.push(lines[i]);
                i++;
            }
            if (p.length) html.push('<p>' + processInline(p.join('\n')) + '</p>');
        }

        return html.join('\n');
    }

    /* ---- table helpers ---- */
    function parseTR(line) {
        line = line.trim();
        if (line.startsWith('|')) line = line.slice(1);
        if (line.endsWith('|')) line = line.slice(0, -1);
        return line.split('|').map(function (c) { return c.trim(); });
    }

    /**
     * 校验 URL 协议是否安全。
     * 链接允许：http:、https:、mailto:
     * 图片额外允许：data:image/
     * 相对地址（无协议）视为安全。
     */
    function isSafeUrl(url, isImage) {
        if (!url) return false;
        var trimmed = url.replace(/^\s+/, '');
        var lower = trimmed.toLowerCase();
        // 提取协议部分（首个冒号之前）；若冒号出现在 / ? # 之后则视为相对地址
        var colonIdx = lower.indexOf(':');
        var slashIdx = lower.search(/[\/?#]/);
        if (colonIdx === -1 || (slashIdx !== -1 && slashIdx < colonIdx)) {
            return true; // 相对地址，安全
        }
        if (isImage && lower.indexOf('data:image/') === 0) return true;
        return lower.indexOf('http:') === 0
            || lower.indexOf('https:') === 0
            || lower.indexOf('mailto:') === 0;
    }

    /* ---- inline parser ---- */
    function processInline(text) {
        if (!text) return '';
        var codes = [];
        // protect inline code
        text = text.replace(/`([^`]+)`/g, function (_, c) {
            codes.push('<code>' + escapeHtml(c) + '</code>');
            return '\x00C' + (codes.length - 1) + '\x00';
        });

        text = escapeHtml(text);

        // images — 生成的图像已经由专属的工具结果卡片（generated-image-card）内联展示，
        // 这里不再把 AI 回复文本中的 ![]() 渲染成 <img>，否则同一张图会重复显示两次。
        // 仅允许 http/https/mailto/data:image 协议时渲染为可点击链接，其余情况仅保留替代文本。
        text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, function (m, alt, url) {
            if (isSafeUrl(url, true)) {
                var label = alt && alt.length > 0 ? alt : url;
                return '<a href="' + url + '" target="_blank">' + label + '</a>';
            }
            return alt; // 协议不允许时仅渲染替代文本
        });
        // links — 仅允许 http/https/mailto 协议
        text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, function (m, label, url) {
            if (isSafeUrl(url, false)) {
                return '<a href="' + url + '" target="_blank">' + label + '</a>';
            }
            return label; // 协议不允许时仅渲染链接文本，不包裹 <a>
        });
        // bold
        text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        text = text.replace(/__(.+?)__/g, '<strong>$1</strong>');
        // italic
        text = text.replace(/\*(.+?)\*/g, '<em>$1</em>');
        text = text.replace(/_(.+?)_/g, '<em>$1</em>');
        // strikethrough
        text = text.replace(/~~(.+?)~~/g, '<del>$1</del>');
        // line breaks
        text = text.replace(/\n/g, '<br>');

        // restore inline code
        for (var j = 0; j < codes.length; j++) {
            text = text.replace('\x00C' + j + '\x00', codes[j]);
        }
        return text;
    }

    return { render: render, escapeHtml: escapeHtml };
})();
