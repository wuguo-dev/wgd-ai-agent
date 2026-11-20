<script setup>
import '../assets/styles/auth-form.css';
</script>
<template>
    <div class="auth-page">
        <header class="auth-header">
            <RouterLink class="brand" to="/login"
                ><span class="brand-mark" aria-hidden="true">知</span
                >知识问答</RouterLink
            ><span class="header-caption">校园知识共享与智能学习平台</span>
        </header>
        <main class="auth-main">
            <section class="introduction" aria-labelledby="platform-title">
                <p class="eyebrow">校园学习空间</p>
                <h1 id="platform-title">
                    校园知识共享与 AI 学习平台
                </h1>
                <p class="introduction-copy">
                    提供社区问答、AI 知识库检索对话与历史会话查询。
                </p>
                <div class="platform-features">
                    <article>
                        <span class="feature-mark" aria-hidden="true">问</span>
                        <div>
                            <h2>和同学交流</h2>
                            <p>发布问题、分享回答，收藏值得继续思考的内容。</p>
                        </div>
                    </article>
                    <article>
                        <span class="feature-mark" aria-hidden="true">AI</span>
                        <div>
                            <h2>用 AI 拓展思路</h2>
                            <p>流式对话、知识库参考，帮助你拆解复杂问题。</p>
                        </div>
                    </article>
                    <article>
                        <span class="feature-mark" aria-hidden="true">记</span>
                        <div>
                            <h2>保留学习轨迹</h2>
                            <p>从个人记录与历史会话，继续上一次的探索。</p>
                        </div>
                    </article>
                </div>
            </section>
            <section class="auth-form-region" aria-label="账号认证">
                <RouterView />
            </section>
        </main>
        <footer class="auth-footer">
            知识问答 · 校园知识共享与智能学习平台
        </footer>
    </div>
</template>
<style scoped>
.auth-page {
    min-height: 100dvh;
    display: flex;
    flex-direction: column;
    background: var(--color-background);
}
.auth-header {
    width: min(1320px, 100%);
    padding: 32px 48px;
    margin: auto;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.header-caption {
    font-size: 13px;
    color: var(--color-muted);
}
.auth-main {
    width: min(1184px, 100%);
    margin: auto;
    padding: 48px;
    display: grid;
    grid-template-columns: 1.1fr 1fr;
    gap: clamp(48px, 7vw, 100px);
    align-items: center;
}
.introduction h1 {
    font-size: clamp(32px, 3.5vw, 46px);
    line-height: 1.4;
    letter-spacing: -1.5px;
    margin-bottom: 24px;
}
.introduction-copy {
    color: var(--color-muted);
    font-size: 16px;
    line-height: 1.9;
}
.platform-features {
    margin-top: 40px;
    display: grid;
    gap: 24px;
}
.platform-features article {
    display: flex;
    gap: 16px;
    align-items: start;
}
.feature-mark {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    border-radius: 12px;
    color: var(--color-primary);
    background: var(--el-color-primary-light-9);
    font-size: 17px;
    font-weight: 700;
}
.platform-features h2 {
    font-size: 15px;
    margin: 0 0 4px;
}
.platform-features p {
    font-size: 13px;
    color: var(--color-muted);
    margin: 0;
}
.auth-form-region {
    min-width: 0;
}
.auth-footer {
    text-align: center;
    color: var(--color-muted);
    font-size: 12px;
    padding: 24px;
}
@media (max-width: 1000px) {
    .auth-main {
        gap: 40px;
        padding: 32px;
    }
    .auth-header {
        padding: 28px 32px;
    }
    .introduction h1 {
        font-size: 34px;
    }
}
@media (max-width: 768px) {
    .auth-header {
        padding: 24px;
    }
    .header-caption {
        display: none;
    }
    .auth-main {
        display: flex;
        flex-direction: column;
        gap: 28px;
        padding: 20px 24px 32px;
        width: min(510px, 100%);
    }
    .introduction,
    .auth-form-region {
        width: 100%;
    }
    .introduction h1 {
        font-size: 30px;
        margin-bottom: 16px;
    }
    .introduction-copy {
        font-size: 14px;
    }
    .platform-features {
        display: none;
    }
    .auth-footer {
        padding: 20px;
    }
    .introduction .eyebrow {
        margin-bottom: 12px;
    }
}
@media (max-width: 400px) {
    .auth-main {
        padding: 12px 18px 28px;
    }
    .auth-header {
        padding: 20px;
    }
    .introduction h1 {
        font-size: 27px;
    }
}
</style>
