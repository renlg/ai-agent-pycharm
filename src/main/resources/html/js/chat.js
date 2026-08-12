/**
 * Chat UI Controller
 * Handles DOM manipulation, Java <-> JS bridge, session management
 */
(function () {
    'use strict';

    /* ===== State ===== */
    var TOKEN_BUDGET = 200000;
    var isProcessing = false;
    var currentAssistantEl = null;
    var currentContentEl = null;
    var accumulatedContent = '';
    var messageQueue = [];
    var ready = false;
    var totalUsedTokens = 0;
    var pendingImages = [];
    var visionCapable = false;
    var safetyTimeoutId = null; // 安全超时定时器，防止 UI 永久卡住
    var pendingChunks = []; // 待写入 DOM 的流式文本片段，按 rAF 节流批量刷新
    var flushRafId = null;

    /* ===== DOM refs ===== */
    var messagesArea, welcomeScreen, messageInput, sendBtn, inputWrapper;
    var tabList, modelDropdown, modelDropdownTrigger, modelDropdownMenu, modelDropdownLabel;
    var modeDropdown, modeDropdownTrigger, modeDropdownMenu, modeDropdownLabel;
    var newSessionBtn;
    var settingsBtn, settingsDropdown, settingsDropdownMenu, skillManagerItem, skillsBadge;
    var memoryManagerItem, memoryBadge;
    var mcpSettingsItem;
    var toolManagerItem;
    var imagePreviewArea;
    var mentionDropdown;
    var mentionItems = [];
    var mentionActiveIndex = -1;
    var mentionTriggerIndex = -1; // cursor position where @ was typed

    /* ===== Init ===== */
    document.addEventListener('DOMContentLoaded', function () {
        messagesArea = document.getElementById('messagesArea');
        welcomeScreen = document.getElementById('welcomeScreen');
        messageInput = document.getElementById('messageInput');
        sendBtn = document.getElementById('sendBtn');
        tabList = document.getElementById('tabList');
        modelDropdown = document.getElementById('modelDropdown');
        modelDropdownTrigger = document.getElementById('modelSelectTrigger');
        modelDropdownMenu = document.getElementById('modelDropdownMenu');
        modelDropdownLabel = document.getElementById('modelDropdownLabel');
        newSessionBtn = document.getElementById('newSessionBtn');
        modeDropdown = document.getElementById('modeDropdown');
        modeDropdownTrigger = document.getElementById('modeDropdownTrigger');
        modeDropdownMenu = document.getElementById('modeDropdownMenu');
        modeDropdownLabel = document.getElementById('modeDropdownLabel');
        inputWrapper = document.querySelector('.input-wrapper');
        settingsBtn = document.getElementById('settingsBtn');
        settingsDropdown = document.getElementById('settingsDropdown');
        settingsDropdownMenu = document.getElementById('settingsDropdownMenu');
        skillManagerItem = document.getElementById('skillManagerItem');
        skillsBadge = document.getElementById('skillsBadge');
        memoryManagerItem = document.getElementById('memoryManagerItem');
        memoryBadge = document.getElementById('memoryBadge');
        mcpSettingsItem = document.getElementById('mcpSettingsItem');
        toolManagerItem = document.getElementById('toolManagerItem');
        imagePreviewArea = document.getElementById('imagePreviewArea');
        mentionDropdown = document.getElementById('mentionDropdown');

        if (window.__TAIW_THEME__ === 'dark') {
            document.body.classList.add('dark');
        }

        messageInput.addEventListener('keydown', function (e) {
            // Mention dropdown navigation
            if (mentionDropdown && mentionDropdown.style.display !== 'none') {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    navigateMention(1);
                    return;
                }
                if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    navigateMention(-1);
                    return;
                }
                if (e.key === 'Enter' || e.key === 'Tab') {
                    if (mentionActiveIndex >= 0 && mentionActiveIndex < mentionItems.length) {
                        e.preventDefault();
                        selectMention(mentionActiveIndex);
                        return;
                    }
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    hideMentionDropdown();
                    return;
                }
            }
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });

        messageInput.addEventListener('input', function () {
            autoResize();
            checkMentionTrigger();
        });

        messageInput.addEventListener('paste', function (e) {
            handlePaste(e);
        });

        messageInput.addEventListener('drop', function (e) {
            handleDrop(e);
        });

        messageInput.addEventListener('dragover', function (e) {
            e.preventDefault();
        });

        sendBtn.addEventListener('click', function () {
            if (isProcessing) {
                stopGeneration();
            } else {
                sendMessage();
            }
        });

        newSessionBtn.addEventListener('click', function () {
            createNewSession();
        });

        modelDropdownTrigger.addEventListener('click', function (e) {
            e.stopPropagation();
            modelDropdown.classList.toggle('open');
            // 关闭模式下拉框和设置下拉框
            modeDropdown.classList.remove('open');
            settingsDropdown.classList.remove('open');
        });

        modeDropdownTrigger.addEventListener('click', function (e) {
            e.stopPropagation();
            modeDropdown.classList.toggle('open');
            // 关闭模型下拉框和设置下拉框
            modelDropdown.classList.remove('open');
            settingsDropdown.classList.remove('open');
        });

        settingsBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            settingsDropdown.classList.toggle('open');
            // 关闭模式和模型下拉框
            modeDropdown.classList.remove('open');
            modelDropdown.classList.remove('open');
        });

        skillManagerItem.addEventListener('click', function (e) {
            e.stopPropagation();
            callJava('openSkillManager', {});
            settingsDropdown.classList.remove('open');
        });

        memoryManagerItem.addEventListener('click', function (e) {
            e.stopPropagation();
            callJava('openMemoryManager', {});
            settingsDropdown.classList.remove('open');
        });

        mcpSettingsItem.addEventListener('click', function (e) {
            e.stopPropagation();
            callJava('openMcpSettings', {});
            settingsDropdown.classList.remove('open');
        });

        toolManagerItem.addEventListener('click', function (e) {
            e.stopPropagation();
            callJava('openToolManager', {});
            settingsDropdown.classList.remove('open');
        });

        document.addEventListener('click', function () {
            modelDropdown.classList.remove('open');
            modeDropdown.classList.remove('open');
            settingsDropdown.classList.remove('open');
        });

        ready = true;
        flushQueue();
        initTokenProgress();
    });

    function flushQueue() {
        while (messageQueue.length > 0) {
            var fn = messageQueue.shift();
            fn();
        }
    }

    function whenReady(fn) {
        if (ready) { fn(); } else { messageQueue.push(fn); }
    }

    /* ===== Auto-resize textarea ===== */
    function autoResize() {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 160) + 'px';
    }

    /* ===== User actions ===== */
    function sendMessage() {
        var text = messageInput.value.trim();
        if ((!text && pendingImages.length === 0) || isProcessing) return;

        hideMentionDropdown();
        messageInput.value = '';
        messageInput.style.height = 'auto';
        isProcessing = true;
        setButtonToStop();

        var images = pendingImages.slice();
        clearImagePreviews();

        appendUserMessage(text, images);
        showThinking();

        var imageData = [];
        for (var i = 0; i < images.length; i++) {
            imageData.push({ base64: images[i].base64, mimeType: images[i].mimeType });
        }
        callJava('sendMessage', { content: text, images: imageData });

        // 启动安全超时定时器（200秒），防止 Java 端未回调导致 UI 永久卡住
        if (safetyTimeoutId) clearTimeout(safetyTimeoutId);
        safetyTimeoutId = setTimeout(function() {
            if (isProcessing) {
                console.warn('Safety timeout triggered, resetting UI state');
                stopGeneration();
                window.onError('', true);
            }
        }, 200000);
    }

    function stopGeneration() {
        callJava('stopGeneration', {});
    }

    function setButtonToStop() {
        sendBtn.classList.add('stop-mode');
        sendBtn.innerHTML = '&#x25A0;'; // ■ 停止符号
        sendBtn.title = '\u505c\u6b62\u751f\u6210';
    }

    function setButtonToSend() {
        sendBtn.classList.remove('stop-mode');
        sendBtn.innerHTML = '&#x27A4;'; // ➤ 发送符号
        sendBtn.title = '\u53d1\u9001 (Enter)';
    }

    function clearChat() {
        clearMessages();
        callJava('clearChat', {});
    }

    function createNewSession() {
        clearMessages();
        callJava('createSession', {});
    }

    function switchSession(id) {
        clearMessages();
        callJava('switchSession', { sessionId: id });
    }

    function deleteSession(id) {
        callJava('deleteSession', { sessionId: id });
    }

    function callJava(action, data) {
        if (typeof window.taiweiQuery === 'function') {
            window.taiweiQuery(JSON.stringify({ action: action, data: data }));
        }
    }

    /* ===== Java -> JS API ===== */

    // 将 DOM 写入从「每个 chunk 一次」降为「每帧一次」，避免高频流式文本导致频繁重排/重绘
    function flushPendingChunks() {
        flushRafId = null;
        if (pendingChunks.length === 0) return;
        var content = pendingChunks.join('');
        pendingChunks = [];

        accumulatedContent += content;

        if (currentAssistantEl && currentContentEl) {
            // 纯文本增量追加，不触发 Markdown 渲染
            currentContentEl.textContent += content;
            keepThinkingAtBottom();
            scrollToBottom();
            return;
        }

        // 首次：创建 assistant 消息框（不关闭 thinking 动画，等 onComplete/onError 时再关闭）
        removeWelcome();

        var msg = createMessageEl('assistant', 'AI');
        currentAssistantEl = msg;
        currentContentEl = msg.querySelector('.message-content');
        // 初始内容用 textContent 设置
        currentContentEl.textContent = accumulatedContent;
        keepThinkingAtBottom();
        scrollToBottom();
    }

    window.appendContent = function (content) {
        whenReady(function () {
            pendingChunks.push(content);
            if (flushRafId === null) {
                flushRafId = requestAnimationFrame(flushPendingChunks);
            }
        });
    };

    window.showToolCall = function (name, args) {
        whenReady(function () {
            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool';

            var argsHtml = '';
            if (args) {
                var display = args.length > 200 ? args.substring(0, 200) + '...' : args;
                argsHtml = '<div class="tool-args">' + MarkdownRenderer.escapeHtml(display) + '</div>';
            }

            el.innerHTML =
                '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">' +
                MarkdownRenderer.escapeHtml(name) + '</span></div>' +
                argsHtml +
                '<div class="tool-status">&#x6267;&#x884c;&#x4e2d;...</div>';

            messagesArea.appendChild(el);
            keepThinkingAtBottom();
            scrollToBottom();
        });
    };

    window.updateToolCall = function (name, result) {
        whenReady(function () {
            var cards = messagesArea.querySelectorAll('.message.tool');
            for (var i = cards.length - 1; i >= 0; i--) {
                var card = cards[i];
                var nameEl = card.querySelector('.tool-name');
                if (nameEl && nameEl.textContent === name) {
                    var statusEl = card.querySelector('.tool-status');
                    if (statusEl) statusEl.remove();

                    var existing = card.querySelector('.tool-result');
                    if (!existing) {
                        var resDiv = document.createElement('div');
                        resDiv.className = 'tool-result';
                        var resDisplay = result && result.length > 500 ? result.substring(0, 500) + '\n...' : (result || '');
                        resDiv.textContent = resDisplay;
                        card.appendChild(resDiv);
                    }
                    break;
                }
            }
            keepThinkingAtBottom();
            scrollToBottom();
        });
    };

    /* ===== 进度条 UI ===== */
    window.showProgress = function (toolCallId, status) {
        whenReady(function () {
            var existing = document.getElementById('progress-' + toolCallId);
            if (existing) {
                var statusEl = existing.querySelector('.command-status-text');
                if (statusEl) {
                    var display = status.length > 100 ? status.substring(0, 100) + '...' : status;
                    statusEl.textContent = display;
                }
                // 如果是完成状态，移除进度条
                if (status.indexOf('\u2705') !== -1) {
                    var bar = existing.querySelector('.command-progress-bar');
                    if (bar) bar.remove();
                }
                return;
            }

            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool command-progress';
            el.id = 'progress-' + toolCallId;
            el.innerHTML =
                '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">' + MarkdownRenderer.escapeHtml(toolCallId) + '</span></div>' +
                '<div class="command-progress-bar"><div class="command-progress-indeterminate"></div></div>' +
                '<div class="command-status-text">' + MarkdownRenderer.escapeHtml(status) + '</div>';
            messagesArea.appendChild(el);
            keepThinkingAtBottom();
            scrollToBottom();
        });
    };

    window.hideProgress = function (toolCallId) {
        whenReady(function () {
            var el = document.getElementById('progress-' + toolCallId);
            if (el) el.remove();
        });
    };

    window.clearAllProgress = function () {
        whenReady(function () {
            var els = document.querySelectorAll('[id^="progress-"]');
            for (var i = 0; i < els.length; i++) { els[i].remove(); }
        });
    };

    /* ===== 危险命令运行按钮 ===== */
    window.showRunButton = function (toolCallId, command) {
        whenReady(function () {
            var existing = document.getElementById('runbtn-' + toolCallId);
            if (existing) return;

            // 不同迭代的文本之间加换行分隔
            if (accumulatedContent.length > 0 && !accumulatedContent.endsWith('\n')) {
                accumulatedContent += '\n';
                if (currentContentEl) {
                    currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                    scrollToBottom();
                }
            }
            // 不要重置 assistant 引用，保持当前 assistant 消息框继续渲染

            var el = document.createElement('div');
            el.className = 'message tool command-run-container';
            el.id = 'runbtn-' + toolCallId;

            var cmdDisplay = command.length > 400 ? command.substring(0, 400) + '...' : command;

            el.innerHTML =
                '<div class="message-label">&#x26a0;&#xfe0f; &#x5de5;&#x5177; &middot; <span class="tool-name">run_command</span></div>' +
                '<div class="command-warning">&#x26a0;&#xfe0f; &#x6b64;&#x547d;&#x4ee4;&#x88ab;&#x8bc6;&#x522b;&#x4e3a;&#x5371;&#x9669;&#x547d;&#x4ee4;&#xff0c;&#x8bf7;&#x786e;&#x8ba4;&#x540e;&#x6267;&#x884c;</div>' +
                '<div class="command-text">' + MarkdownRenderer.escapeHtml(cmdDisplay) + '</div>' +
                '<button class="command-run-btn" onclick="window.runCommandAction(\'' + toolCallId + '\')">&#x25b6; &#x8fd0;&#x884c;</button>';

            messagesArea.appendChild(el);
            keepThinkingAtBottom();
            scrollToBottom();
        });
    };

    // 运行按钮点击处理
    window.runCommandAction = function (toolCallId) {
        callJava('runCommand', { toolCallId: toolCallId });
    };

    window.hideRunButton = function (toolCallId) {
        whenReady(function () {
            var el = document.getElementById('runbtn-' + toolCallId);
            if (el) {
                // 替换为"已执行"状态
                el.innerHTML =
                    '<div class="message-label">&#x1f527; &#x5de5;&#x5177; &middot; <span class="tool-name">run_command</span></div>' +
                    '<div class="command-status-text">&#x5df2;&#x6267;&#x884c;</div>';
            }
        });
    };

    window.clearAllRunButtons = function () {
        whenReady(function () {
            var els = document.querySelectorAll('[id^="runbtn-"]');
            for (var i = 0; i < els.length; i++) { els[i].remove(); }
        });
    };

    window.onComplete = function () {
        whenReady(function () {
            // 清除安全超时定时器
            if (safetyTimeoutId) {
                clearTimeout(safetyTimeoutId);
                safetyTimeoutId = null;
            }
            removeThinking();
            removeRoundLoading();
            isProcessing = false;
            setButtonToSend();
            sendBtn.disabled = false;

            // 确保所有排队的 chunk 都已写入 accumulatedContent，再做最终渲染
            if (flushRafId !== null) {
                cancelAnimationFrame(flushRafId);
                flushRafId = null;
            }
            flushPendingChunks();

            // 流式完成后做一次完整 Markdown 渲染
            if (currentContentEl && accumulatedContent.length > 0) {
                currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
                scrollToBottom();
            }
            
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
        });
    };

    window.onError = function (error, suppressDisplay) {
        whenReady(function () {
            // 清除安全超时定时器
            if (safetyTimeoutId) {
                clearTimeout(safetyTimeoutId);
                safetyTimeoutId = null;
            }
            removeThinking();
            removeRoundLoading();

            // 确保所有排队的 chunk 都已写入 accumulatedContent，再做最终渲染
            if (flushRafId !== null) {
                cancelAnimationFrame(flushRafId);
                flushRafId = null;
            }
            flushPendingChunks();

            // 先做最终 Markdown 渲染
            if (currentContentEl && accumulatedContent.length > 0) {
                currentContentEl.innerHTML = MarkdownRenderer.render(accumulatedContent);
            }
            if (!suppressDisplay) {
                createMessageEl('error', '❌ 错误').querySelector('.message-content').textContent = error;
            }
            isProcessing = false;
            setButtonToSend();
            sendBtn.disabled = false;
            currentAssistantEl = null;
            currentContentEl = null;
            accumulatedContent = '';
            scrollToBottom();
        });
    };

    window.updateTokenUsage = function (data) {
        whenReady(function () {
            var usage = data.usage || {};
            var elapsedMs = data.elapsedMs || 0;
            totalUsedTokens += usage.totalTokens || 0;

            var statsEl = document.createElement('div');
            statsEl.className = 'token-stats';
            var elapsed = (elapsedMs / 1000).toFixed(1);
            statsEl.innerHTML =
                '<span>\u8f93\u5165: ' + (usage.promptTokens || 0).toLocaleString() + '</span>' +
                '<span>\u8f93\u51fa: ' + (usage.completionTokens || 0).toLocaleString() + '</span>' +
                '<span>\u672c\u8f6e\u6d88\u8017: ' + (usage.totalTokens || 0).toLocaleString() + '</span>' +
                '<span>\u8017\u65f6: ' + elapsed + 's</span>';
            messagesArea.appendChild(statsEl);

            updateTokenProgressRing();
            scrollToBottom();
        });
    };

    window.updateSessionList = function (sessions, activeId) {
        whenReady(function () {
            var sessionList;
            if (typeof sessions === 'string') {
                try { sessionList = JSON.parse(sessions); } catch (e) { return; }
            } else {
                sessionList = sessions;
            }
            renderSessionTabs(sessionList, activeId);
        });
    };

    window.updateMode = function (mode) {
        whenReady(function () {
            if (!modeDropdownLabel || !modeDropdownMenu) return;
            var isPlan = mode === 'plan';
            modeDropdownLabel.textContent = isPlan ? '\uD83D\uDFE1 Plan' : '\uD83D\uDFE2 Build';

            // 渲染下拉框选项
            modeDropdownMenu.innerHTML = '';
            var modes = [
                { value: 'build', label: '\uD83D\uDFE2 Build', desc: '\u6b63\u5e38\u8bfb\u5199' },
                { value: 'plan', label: '\uD83D\uDFE1 Plan', desc: '\u53ea\u8bfb\u5206\u6790' }
            ];
            for (var i = 0; i < modes.length; i++) {
                var item = document.createElement('div');
                item.className = 'mode-dropdown-item' + (modes[i].value === mode ? ' active' : '');
                item.setAttribute('data-mode', modes[i].value);
                item.innerHTML = '<span>' + modes[i].label + '</span><span style="color:var(--text-tertiary);font-size:11px">' + modes[i].desc + '</span>';
                item.addEventListener('click', function (e) {
                    e.stopPropagation();
                    var selectedMode = this.getAttribute('data-mode');
                    callJava('setMode', { mode: selectedMode });
                    modeDropdown.classList.remove('open');
                });
                modeDropdownMenu.appendChild(item);
            }
        });
    };

    window.updateSkillsCount = function (count) {
        whenReady(function () {
            if (skillsBadge) skillsBadge.textContent = count;
        });
    };

    window.updateMemoriesCount = function (count) {
        whenReady(function () {
            if (memoryBadge) memoryBadge.textContent = count;
        });
    };

    window.updateVisionCapable = function (capable) {
        visionCapable = capable;
    };

    window.updateModelList = function (models, activeIndex) {
        whenReady(function () {
            var modelList;
            if (typeof models === 'string') {
                try { modelList = JSON.parse(models); } catch (e) { return; }
            } else {
                modelList = models;
            }
            renderModelSelect(modelList, activeIndex);
        });
    };

    window.loadHistory = function (messagesJson, isActiveProcessing, totalTokens) {
        whenReady(function () {
            clearMessages();
            totalUsedTokens = totalTokens || 0;
            if (totalUsedTokens > 0) {
                updateTokenProgressRing();
            }
            try {
                var messages;
                if (typeof messagesJson === 'string') {
                    messages = JSON.parse(messagesJson);
                } else {
                    messages = messagesJson || [];
                }
                for (var i = 0; i < messages.length; i++) {
                    var m = messages[i];
                    if (m.role === 'user') {
                        appendUserMessage(m.content, m.images);
                    } else if (m.role === 'assistant' && m.content) {
                        var el = createMessageEl('assistant', 'AI');
                        el.querySelector('.message-content').innerHTML = MarkdownRenderer.render(m.content);
                    }
                }

                // 恢复流式传输状态
                if (isActiveProcessing && messages.length > 0) {
                    isProcessing = true;
                    setButtonToStop();

                    // 找到最后一个 assistant 消息，用于后续 appendContent 追加
                    var assistantEls = messagesArea.querySelectorAll('.message.assistant');
                    if (assistantEls.length > 0) {
                        var lastAssistant = assistantEls[assistantEls.length - 1];
                        currentAssistantEl = lastAssistant;
                        var contentEl = lastAssistant.querySelector('.message-content');
                        if (contentEl) {
                            currentContentEl = contentEl;
                            // 用 textContent 获取纯文本长度（与 accumulatedContent 对齐）
                            accumulatedContent = contentEl.textContent || '';
                        }
                    }

                    // 如果没有 assistant 消息但有流式状态，显示思考指示器
                    if (assistantEls.length === 0) {
                        showThinking();
                    }
                }
            } catch (e) { /* ignore parse errors */ }
            scrollToBottom();
        });
    };

    /* ===== Top Bar: Session Tabs ===== */

    function renderSessionTabs(sessions, activeId) {
        if (!tabList) return;
        tabList.innerHTML = '';

        for (var i = 0; i < sessions.length; i++) {
            var s = sessions[i];
            var isActive = s.id === activeId;
            var tab = document.createElement('div');
            tab.className = 'tab-item' + (isActive ? ' active' : '');
            tab.setAttribute('data-id', s.id);

            var titleSpan = document.createElement('span');
            titleSpan.className = 'tab-title';
            var title = s.title || '\u65b0\u4f1a\u8bdd';
            titleSpan.textContent = title.length > 12 ? title.substring(0, 12) + '...' : title;
            titleSpan.addEventListener('click', function (sid) {
                return function () { switchSession(sid); };
            }(s.id));
            tab.appendChild(titleSpan);

            var closeBtn = document.createElement('span');
            closeBtn.className = 'tab-close';
            closeBtn.innerHTML = '\u00d7';
            closeBtn.title = '\u5173\u95ed\u4f1a\u8bdd';
            closeBtn.addEventListener('click', function (sid, evt) {
                return function (e) {
                    e.stopPropagation();
                    deleteSession(sid);
                };
            }(s.id));
            tab.appendChild(closeBtn);

            tabList.appendChild(tab);
        }
    }

    /* ===== Top Bar: Model Selector ===== */

    function renderModelSelect(models, activeIndex) {
        if (!modelDropdownMenu || !modelDropdownLabel) return;
        modelDropdownMenu.innerHTML = '';

        for (var i = 0; i < models.length; i++) {
            var item = document.createElement('div');
            item.className = 'model-dropdown-item' + (i === activeIndex ? ' active' : '');
            item.textContent = models[i].name;
            item.setAttribute('data-index', i);
            item.addEventListener('click', function (e) {
                e.stopPropagation();
                var idx = parseInt(this.getAttribute('data-index'), 10);
                if (!isNaN(idx)) {
                    callJava('selectModel', { index: idx });
                    modelDropdownLabel.textContent = this.textContent;
                    modelDropdown.classList.remove('open');
                    // Update active state
                    var items = modelDropdownMenu.querySelectorAll('.model-dropdown-item');
                    for (var j = 0; j < items.length; j++) {
                        items[j].classList.remove('active');
                    }
                    this.classList.add('active');
                }
            });
            modelDropdownMenu.appendChild(item);
        }

        // Set label to active model
        if (models[activeIndex]) {
            modelDropdownLabel.textContent = models[activeIndex].name;
        }
    }

    /* ===== DOM helpers ===== */

    function appendUserMessage(text, images) {
        removeWelcome();
        var el = createMessageEl('user', '&#x1f464; &#x4f60;');
        var contentEl = el.querySelector('.message-content');
        if (text) {
            contentEl.textContent = text;
        }
        if (images && images.length > 0) {
            var imgContainer = document.createElement('div');
            imgContainer.className = 'message-images';
            for (var i = 0; i < images.length; i++) {
                var img = document.createElement('img');
                img.src = images[i].dataUrl;
                img.className = 'message-image-thumb';
                imgContainer.appendChild(img);
            }
            contentEl.appendChild(imgContainer);
        }
        scrollToBottom();
    }

    function renderAssistantContent(content) {
        if (!currentContentEl) return;
        currentContentEl.innerHTML = MarkdownRenderer.render(content);
        scrollToBottom();
    }

    function createMessageEl(type, labelHtml) {
        removeWelcome();
        var el = document.createElement('div');
        el.className = 'message ' + type;
        el.innerHTML =
            '<div class="message-label">' + labelHtml + '</div>' +
            '<div class="message-content"></div>';
        messagesArea.appendChild(el);
        return el;
    }

    function showThinking() {
        var el = document.getElementById('thinkingIndicator');
        if (!el) {
            el = document.createElement('div');
            el.className = 'thinking-indicator';
            el.id = 'thinkingIndicator';
            el.innerHTML =
                '<div class="thinking-dots"><span></span><span></span><span></span></div>';
        }
        // appendChild 对已在 DOM 中的节点是移动操作：始终保持指示器在消息区底部
        messagesArea.appendChild(el);
        scrollToBottom();
    }

    // 新元素（流式文本、工具卡片、进度条）追加后，把 thinking 指示器挪回底部；
    // 指示器在整个 Agent 循环期间常驻，仅在 onComplete/onError 时移除
    function keepThinkingAtBottom() {
        var el = document.getElementById('thinkingIndicator');
        if (el && el !== messagesArea.lastElementChild) {
            messagesArea.appendChild(el);
        }
    }

    function removeThinking() {
        var el = document.getElementById('thinkingIndicator');
        if (el) el.remove();
    }

    // 暴露给 Java 端调用
    window.showThinking = function () { whenReady(showThinking); };
    window.removeThinking = function () { whenReady(removeThinking); };

    function showRoundLoading() {
        removeRoundLoading();
        var el = document.createElement('div');
        el.className = 'round-loading';
        el.id = 'roundLoadingIndicator';
        el.innerHTML =
            '<div class="round-loading-spinner"></div>' +
            '<span>\u5904\u7406\u4e2d...</span>';
        messagesArea.appendChild(el);
        scrollToBottom();
    }

    function removeRoundLoading() {
        var el = document.getElementById('roundLoadingIndicator');
        if (el) el.remove();
    }

    function removeWelcome() {
        if (welcomeScreen && welcomeScreen.parentNode) {
            welcomeScreen.remove();
        }
    }

    function initTokenProgress() {
        if (!inputWrapper) return;
        var container = document.createElement('div');
        container.className = 'token-progress-container';
        container.innerHTML =
            '<svg class="token-progress-ring" width="24" height="24" viewBox="0 0 24 24">' +
                '<circle class="token-progress-bg" cx="12" cy="12" r="10" />' +
                '<circle class="token-progress-fg" cx="12" cy="12" r="10" />' +
            '</svg>' +
            '<div class="token-progress-tooltip"></div>';
        inputWrapper.appendChild(container);

        container.addEventListener('click', function () {
            container.classList.add('compressing');
            callJava('manualCompress', {});
        });
    }

    window.enableManualCompress = function enableManualCompress() {
        var container = document.querySelector('.token-progress-container');
        if (container) container.classList.remove('compressing');
    };

    function updateTokenProgressRing() {
        var ring = document.querySelector('.token-progress-fg');
        var tooltip = document.querySelector('.token-progress-tooltip');
        var container = document.querySelector('.token-progress-container');
        if (!ring || !tooltip || !container) return;

        var circumference = 2 * Math.PI * 10;
        var progress = Math.min(totalUsedTokens / TOKEN_BUDGET, 1);
        ring.style.strokeDasharray = circumference;
        ring.style.strokeDashoffset = circumference * (1 - progress);

        container.classList.remove('warning', 'danger');
        if (progress > 0.9) {
            container.classList.add('danger');
        } else if (progress > 0.8) {
            container.classList.add('warning');
        }

        container.classList.add('visible');
        var pct = (progress * 100).toFixed(1);
        tooltip.innerHTML = '\u5df2\u7528 ' + totalUsedTokens.toLocaleString() + ' / ' + TOKEN_BUDGET.toLocaleString() + ' tokens<br>(' + pct + '%)';
    }

    function clearMessages() {
        messagesArea.innerHTML = '';
        currentAssistantEl = null;
        currentContentEl = null;
        accumulatedContent = '';
        if (flushRafId !== null) {
            cancelAnimationFrame(flushRafId);
            flushRafId = null;
        }
        pendingChunks = [];
        isProcessing = false;
        totalUsedTokens = 0;
        setButtonToSend();
        sendBtn.disabled = false;
        clearImagePreviews();

        var progressContainer = document.querySelector('.token-progress-container');
        if (progressContainer) {
            progressContainer.classList.remove('visible', 'warning', 'danger');
        }

        // 清理进度条和运行按钮
        var progressEls = document.querySelectorAll('[id^="progress-"]');
        for (var pe = 0; pe < progressEls.length; pe++) { progressEls[pe].remove(); }
        var runbtnEls = document.querySelectorAll('[id^="runbtn-"]');
        for (var re = 0; re < runbtnEls.length; re++) { runbtnEls[re].remove(); }

        var ws = document.createElement('div');
        ws.className = 'welcome';
        ws.id = 'welcomeScreen';
        ws.innerHTML =
            '<div class="welcome-icon">&#x1f916;</div>' +
            '<h2>&#x592a;&#x5fae; AI &#x52a9;&#x624b;</h2>' +
            '<p>&#x4f60;&#x597d;&#xff0c;&#x6211;&#x662f;&#x4f60;&#x7684; AI &#x7f16;&#x7a0b;&#x52a9;&#x624b;&#x3002;&#x8f93;&#x5165;&#x95ee;&#x9898;&#x5f00;&#x59cb;&#x5bf9;&#x8bdd;&#xff0c;&#x6211;&#x53ef;&#x4ee5;&#x5e2e;&#x4f60;&#x7f16;&#x5199;&#x4ee3;&#x7801;&#x3001;&#x5206;&#x6790;&#x95ee;&#x9898;&#x3001;&#x6267;&#x884c;&#x547d;&#x4ee4;&#x3002;</p>';
        messagesArea.appendChild(ws);
        welcomeScreen = ws;
    }

    function scrollToBottom() {
        requestAnimationFrame(function () {
            messagesArea.scrollTop = messagesArea.scrollHeight;
        });
    }

    /* ===== Image Handling ===== */
    function handlePaste(e) {
        var items = e.clipboardData && e.clipboardData.items;
        if (!items) return;
        var handledImage = false;
        // 收集剪贴板中的所有图片项（与 handleDrop 行为一致）
        for (var i = 0; i < items.length; i++) {
            if (items[i].type.indexOf('image') !== -1) {
                var file = items[i].getAsFile();
                if (file) {
                    if (!handledImage) {
                        e.preventDefault();
                        handledImage = true;
                    }
                    if (!visionCapable) {
                        window.showNotification('Current model does not support image input. Please switch to a model that supports images (e.g., GPT-4o, Claude, Gemini, Qwen).');
                        continue;
                    }
                    readFileAsBase64(file);
                }
            }
        }
    }

    function handleDrop(e) {
        e.preventDefault();
        var files = e.dataTransfer && e.dataTransfer.files;
        if (!files) return;
        for (var i = 0; i < files.length; i++) {
            if (files[i].type.indexOf('image') !== -1) {
                if (!visionCapable) {
                    window.showNotification('Current model does not support image input. Please switch to a model that supports images (e.g., GPT-4o, Claude, Gemini, Qwen).');
                    continue;
                }
                readFileAsBase64(files[i]);
            }
        }
    }

    var MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 单张图片最大 10MB
    var MAX_IMAGES = 5;                      // 每条消息最多 5 张图片
    var MAX_IMAGE_DIMENSION = 1568;          // 最长边上限（像素）
    var ALLOWED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

    function readFileAsBase64(file) {
        // 数量限制
        if (pendingImages.length >= MAX_IMAGES) {
            window.showNotification('Too many images. A maximum of ' + MAX_IMAGES + ' images per message is allowed.');
            return;
        }
        // 类型限制
        if (ALLOWED_IMAGE_TYPES.indexOf(file.type) === -1) {
            window.showNotification('Unsupported image type: ' + (file.type || 'unknown') + '. Allowed: PNG, JPEG, GIF, WEBP.');
            return;
        }
        // 大小限制（编码前的原始文件大小）
        if (file.size > MAX_IMAGE_BYTES) {
            window.showNotification('Image is too large (' + (file.size / (1024 * 1024)).toFixed(1) + 'MB). Maximum allowed is 10MB.');
            return;
        }

        var reader = new FileReader();
        reader.onload = function (event) {
            var dataUrl = event.target.result;
            var mimeType = file.type || 'image/png';
            downscaleImage(dataUrl, mimeType, function (finalDataUrl, finalMime) {
                var base64 = finalDataUrl.split(',')[1];
                pendingImages.push({ base64: base64, mimeType: finalMime, dataUrl: finalDataUrl });
                addImagePreview(pendingImages.length - 1, finalDataUrl);
            });
        };
        reader.onerror = function () {
            console.error('Failed to read image file', reader.error);
            window.showNotification('Failed to read the image file. Please try again.');
        };
        reader.readAsDataURL(file);
    }

    /**
     * 将图片缩放到最长边不超过 MAX_IMAGE_DIMENSION，并重新编码。
     * 含透明通道（png/webp/gif）优先输出 PNG，否则输出 JPEG（质量 0.85）。
     * 失败时回退到原始 dataUrl。
     */
    function downscaleImage(dataUrl, mimeType, callback) {
        var img = new Image();
        img.onload = function () {
            try {
                var w = img.naturalWidth || img.width;
                var h = img.naturalHeight || img.height;
                var longest = Math.max(w, h);
                var scale = longest > MAX_IMAGE_DIMENSION ? (MAX_IMAGE_DIMENSION / longest) : 1;
                var targetW = Math.max(1, Math.round(w * scale));
                var targetH = Math.max(1, Math.round(h * scale));

                var canvas = document.createElement('canvas');
                canvas.width = targetW;
                canvas.height = targetH;
                var ctx = canvas.getContext('2d');
                ctx.drawImage(img, 0, 0, targetW, targetH);

                var hasAlpha = mimeType === 'image/png' || mimeType === 'image/webp' || mimeType === 'image/gif';
                var outMime = hasAlpha ? 'image/png' : 'image/jpeg';
                var outDataUrl = hasAlpha
                    ? canvas.toDataURL('image/png')
                    : canvas.toDataURL('image/jpeg', 0.85);
                callback(outDataUrl, outMime);
            } catch (err) {
                console.error('Failed to downscale image', err);
                callback(dataUrl, mimeType);
            }
        };
        img.onerror = function () {
            console.error('Failed to load image for downscaling');
            callback(dataUrl, mimeType);
        };
        img.src = dataUrl;
    }

    function addImagePreview(index, dataUrl) {
        if (!imagePreviewArea) return;
        var wrapper = document.createElement('div');
        wrapper.className = 'image-preview-item';
        wrapper.setAttribute('data-index', index);
        wrapper.innerHTML =
            '<img src="' + dataUrl + '" alt="preview" />' +
            '<span class="image-preview-remove" data-index="' + index + '">&times;</span>';
        wrapper.querySelector('.image-preview-remove').addEventListener('click', function () {
            removeImagePreview(parseInt(this.getAttribute('data-index'), 10));
        });
        imagePreviewArea.appendChild(wrapper);
        imagePreviewArea.style.display = 'flex';
    }

    function removeImagePreview(index) {
        pendingImages.splice(index, 1);
        renderImagePreviews();
    }

    function clearImagePreviews() {
        pendingImages = [];
        renderImagePreviews();
    }

    function renderImagePreviews() {
        if (!imagePreviewArea) return;
        imagePreviewArea.innerHTML = '';
        if (pendingImages.length === 0) {
            imagePreviewArea.style.display = 'none';
            return;
        }
        for (var i = 0; i < pendingImages.length; i++) {
            addImagePreview(i, pendingImages[i].dataUrl);
        }
    }

    /* ===== Compress Notification ===== */
    window.showCompressNotification = function (data) {
        whenReady(function () {
            var parsed;
            if (typeof data === 'string') {
                try { parsed = JSON.parse(data); } catch (e) { return; }
            } else {
                parsed = data;
            }

            var el = document.createElement('div');
            el.className = 'compress-notification';
            el.textContent = '\ud83d\udce6 \u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff08\u538b\u7f29\u524d '
                + parsed.before.toLocaleString() + ' tokens \u2192 \u538b\u7f29\u540e '
                + parsed.after.toLocaleString() + ' tokens\uff0c\u8282\u7701 '
                + parsed.percent + '%\uff09';
            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    /* ===== Notifications ===== */
    window.showNotification = function (message) {
        whenReady(function () {
            var el = document.createElement('div');
            el.className = 'compress-notification';
            el.textContent = message;
            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    window.showCompressFailedNotification = function (reason) {
        whenReady(function () {
            var el = document.createElement('div');
            el.className = 'compress-notification compress-failed';
            el.textContent = '⚠️ 压缩失败：' + reason + '，回退到丢弃旧消息';
            messagesArea.appendChild(el);
            scrollToBottom();
        });
    };

    /* ===== Generated Image Display ===== */

    // Tracks the latest base64/url for each rendered <img id> so the open button always
    // opens the image currently displayed, even after a regenerate replaces it.
    var generatedImageState = {};

    window.showGeneratedImage = function (toolCallId, resultJson) {
        whenReady(function () {
            var parsed;
            try {
                parsed = typeof resultJson === 'string' ? JSON.parse(resultJson) : resultJson;
            } catch (e) {
                return;
            }
            var images = parsed.images || [];
            if (images.length === 0) return;

            var prompt = parsed.prompt || '';
            var size   = parsed.size   || '';

            var card = document.createElement('div');
            card.className = 'message assistant generated-image-card';
            card.id = 'genimg-' + toolCallId;

            var gridHtml = '<div class="generated-image-grid">';
            for (var i = 0; i < images.length; i++) {
                var img = images[i];
                var src = '';
                if (img.base64) {
                    src = 'data:' + (img.mimeType || 'image/png') + ';base64,' + img.base64;
                } else if (img.url) {
                    src = img.url;
                }
                if (!src) continue;

                var imgId = 'genimg-img-' + toolCallId + '-' + i;
                var openBtnId = 'opn-' + toolCallId + '-' + i;
                var regenerateBtnId = 'rgn-' + toolCallId + '-' + i;
                gridHtml +=
                    '<div class="generated-image-item">' +
                        '<img class="generated-image-img" id="' + imgId + '" src="' + src + '" alt="' + MarkdownRenderer.escapeHtml(prompt) + '" />' +
                        '<div class="generated-image-actions">' +
                            '<button class="generated-image-open-btn" id="' + openBtnId + '">打开</button>' +
                            '<button class="generated-image-regenerate-btn" id="' + regenerateBtnId + '">↻ 重新生成</button>' +
                        '</div>' +
                    '</div>';

                // store image data for open/regenerate callbacks; state is mutable so a
                // later regenerate updates what the open button opens
                generatedImageState[imgId] = { base64: img.base64 || '', url: img.url || '' };
                (function (btnId, regenBtnId, imgElId, imgIndex, imgPrompt, imgMime, imgSize) {
                    setTimeout(function () {
                        var btn = document.getElementById(btnId);
                        if (btn) {
                            btn.addEventListener('click', function () {
                                var current = generatedImageState[imgElId] || {};
                                callJava('openImage', {
                                    url: current.url || ''
                                });
                            });
                        }
                        var regenBtn = document.getElementById(regenBtnId);
                        if (regenBtn) {
                            regenBtn.addEventListener('click', function () {
                                regenBtn.disabled = true;
                                regenBtn.textContent = '⏳ 生成中...';
                                callJava('regenerateImage', {
                                    toolCallId: toolCallId,
                                    index:      String(imgIndex),
                                    imgElId:    imgElId,
                                    btnId:      regenBtnId,
                                    prompt:     imgPrompt,
                                    size:       imgSize
                                });
                            });
                        }
                    }, 0);
                })(openBtnId, regenerateBtnId, imgId, i, prompt, img.mimeType || 'image/png', size);
            }
            gridHtml += '</div>';

            card.innerHTML = gridHtml;
            messagesArea.appendChild(card);
            keepThinkingAtBottom();
            scrollToBottom();
        });
    };

    /** Called from Java after regenerateImage completes (success or failure). */
    window.updateGeneratedImage = function (imgElId, btnId, resultJson) {
        whenReady(function () {
            var regenBtn = document.getElementById(btnId);
            var parsed = null;
            try {
                parsed = typeof resultJson === 'string' ? JSON.parse(resultJson) : resultJson;
            } catch (e) {
                parsed = null;
            }

            var images = parsed && parsed.images ? parsed.images : [];
            if (images.length === 0) {
                if (regenBtn) {
                    regenBtn.disabled = false;
                    regenBtn.textContent = '↻ 重新生成';
                }
                showNotification(typeof resultJson === 'string' ? resultJson : '图像重新生成失败');
                return;
            }

            var newImg = images[0];
            var src = '';
            if (newImg.base64) {
                src = 'data:' + (newImg.mimeType || 'image/png') + ';base64,' + newImg.base64;
            } else if (newImg.url) {
                src = newImg.url;
            }

            var imgEl = document.getElementById(imgElId);
            if (imgEl && src) {
                imgEl.src = src;
            }
            if (src) {
                generatedImageState[imgElId] = { base64: newImg.base64 || '', url: newImg.url || '' };
            }
            if (regenBtn) {
                regenBtn.disabled = false;
                regenBtn.textContent = '↻ 重新生成';
            }
        });
    };

    /* ===== Utility ===== */
    window.escapeHtml = function (text) {
        return MarkdownRenderer.escapeHtml(text);
    };

    window.setInputText = function (text) {
        whenReady(function () {
            messageInput.value = text;
            autoResize();
        });
    };

    window.updateTokenBudget = function (contextWindowSize) {
        whenReady(function () {
            var size = parseInt(contextWindowSize) || 0;
            if (size > 0) {
                TOKEN_BUDGET = size;
                updateTokenProgressRing();
            }
        });
    };

    window.updateCompressedTokenCount = function(count) {
        whenReady(function() {
            totalUsedTokens = parseInt(count) || 0;
            var container = document.querySelector('.token-progress-container');
            if (container && totalUsedTokens > 0) {
                container.classList.add('visible');
                updateTokenProgressRing();
            }
        });
    };

    /* ===== @ Mention Autocomplete ===== */

    function checkMentionTrigger() {
        if (!mentionDropdown) return;
        var val = messageInput.value;
        var cursorPos = messageInput.selectionStart;

        // Find @ before cursor: look backwards from cursor for @ that isn't preceded by a word char
        var atIndex = -1;
        for (var i = cursorPos - 1; i >= 0; i--) {
            if (val[i] === '@') {
                // Check that @ is at start of string or preceded by whitespace
                if (i === 0 || /\s/.test(val[i - 1])) {
                    atIndex = i;
                }
                break;
            }
            // If we hit a space or newline before finding @, stop
            if (val[i] === ' ' || val[i] === '\n') break;
        }

        if (atIndex >= 0) {
            var query = val.substring(atIndex + 1, cursorPos);
            // Only show if query looks like a mention (alphanumeric, no spaces)
            if (/^[\w]*$/.test(query)) {
                mentionTriggerIndex = atIndex;
                callJava('getMentionSuggestions', { query: query });
                return;
            }
        }
        hideMentionDropdown();
    }

    window.updateMentionSuggestions = function (suggestionsJson) {
        whenReady(function () {
            var suggestions;
            try {
                suggestions = JSON.parse(suggestionsJson);
            } catch (e) {
                hideMentionDropdown();
                return;
            }
            if (!suggestions || suggestions.length === 0) {
                hideMentionDropdown();
                return;
            }

            mentionItems = suggestions;
            mentionActiveIndex = 0;
            renderMentionDropdown();
            mentionDropdown.style.display = 'block';
        });
    };

    function renderMentionDropdown() {
        if (!mentionDropdown) return;
        var html = '';
        for (var i = 0; i < mentionItems.length; i++) {
            var item = mentionItems[i];
            var activeClass = i === mentionActiveIndex ? ' active' : '';
            html += '<div class="mention-item' + activeClass + '" data-index="' + i + '">'
                + '<span class="mention-keyword">@' + escapeHtmlSimple(item.keyword) + '</span>'
                + '<span class="mention-desc">' + escapeHtmlSimple(item.description) + '</span>'
                + '<span class="mention-type">' + escapeHtmlSimple(item.type) + '</span>'
                + '</div>';
        }
        mentionDropdown.innerHTML = html;

        // Add click handlers
        var items = mentionDropdown.querySelectorAll('.mention-item');
        for (var j = 0; j < items.length; j++) {
            items[j].addEventListener('mousedown', (function (idx) {
                return function (e) {
                    e.preventDefault(); // prevent textarea blur
                    selectMention(idx);
                };
            })(j));
        }
    }

    function navigateMention(direction) {
        if (mentionItems.length === 0) return;
        mentionActiveIndex += direction;
        if (mentionActiveIndex < 0) mentionActiveIndex = mentionItems.length - 1;
        if (mentionActiveIndex >= mentionItems.length) mentionActiveIndex = 0;
        renderMentionDropdown();
    }

    function selectMention(index) {
        if (index < 0 || index >= mentionItems.length) return;
        var item = mentionItems[index];
        var val = messageInput.value;
        var cursorPos = messageInput.selectionStart;

        // Replace @query with @keyword + space
        var before = val.substring(0, mentionTriggerIndex);
        var after = val.substring(cursorPos);
        messageInput.value = before + '@' + item.keyword + ' ' + after;

        // Set cursor after the inserted mention
        var newPos = before.length + item.keyword.length + 2; // @keyword + space
        messageInput.setSelectionRange(newPos, newPos);
        messageInput.focus();
        autoResize();
        hideMentionDropdown();
    }

    function hideMentionDropdown() {
        if (mentionDropdown) {
            mentionDropdown.style.display = 'none';
        }
        mentionItems = [];
        mentionActiveIndex = -1;
        mentionTriggerIndex = -1;
    }

    function escapeHtmlSimple(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

})();
