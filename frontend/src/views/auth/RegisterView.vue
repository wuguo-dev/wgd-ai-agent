<script setup>
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from 'element-plus';
import 'element-plus/theme-chalk/el-alert.css';
import 'element-plus/theme-chalk/el-button.css';
import 'element-plus/theme-chalk/el-form.css';
import 'element-plus/theme-chalk/el-form-item.css';
import 'element-plus/theme-chalk/el-input.css';
import PasswordInput from '../../components/PasswordInput.vue';
import { registerStudent } from '../../api/auth.js';

const router = useRouter();
const route = useRoute();
const form = ref();
const submitting = ref(false);
const errorMessage = ref('');
const input = reactive({ username: '', password: '', confirmPassword: '' });
const rules = {
    username: [
        { required: true, message: '请输入用户名', trigger: ['blur', 'change'] },
        { pattern: /^[A-Za-z][A-Za-z0-9_]{2,31}$/, message: '3至32位字母、数字或下划线，以字母开头', trigger: ['blur', 'change'] },
    ],
    password: [{ validator: (_rule, value, callback) => {
        const valid = value.trim().length > 0 && value.length >= 6
            && new TextEncoder().encode(value).length <= 72;
        callback(valid ? undefined : new Error('至少6位，最多72个UTF-8字节'));
    }, trigger: ['blur', 'change'] }],
    confirmPassword: [{ validator: (_rule, value, callback) => {
        callback(value && value === input.password ? undefined : new Error('两次输入的密码需一致'));
    }, trigger: ['blur', 'change'] }],
};

function revalidateConfirmation() {
    if (input.confirmPassword) void form.value?.validateField('confirmPassword').catch(() => {});
}

async function submit() {
    if (submitting.value) return;
    submitting.value = true;
    errorMessage.value = '';
    try {
        const valid = await form.value.validate().catch(() => false);
        if (!valid) return;
        await registerStudent(input.username, input.password, input.confirmPassword);
        input.password = '';
        input.confirmPassword = '';
        await router.replace({ name: 'login', query: { registered: '1', redirect: route.query.redirect } });
    } catch (error) {
        errorMessage.value = error.message;
    } finally {
        submitting.value = false;
    }
}
</script>

<template>
    <div class="auth-card">
        <p class="eyebrow">学生账号注册</p>
        <h2>创建学生账号</h2>
        <p class="auth-subtitle">注册成功后，使用新账号登录。</p>
        <ElAlert v-if="errorMessage" class="auth-alert" :title="errorMessage" type="error" :closable="false" show-icon role="alert" />
        <ElForm ref="form" :model="input" :rules="rules" label-position="top" size="large" hide-required-asterisk @submit.prevent="submit">
            <ElFormItem label="用户名" prop="username" for="register-username">
                <ElInput id="register-username" v-model="input.username" name="username" autocomplete="username" placeholder="以字母开头，3至32位" :maxlength="32" :disabled="submitting" />
            </ElFormItem>
            <ElFormItem label="密码" prop="password" for="register-password">
                <PasswordInput id="register-password" v-model="input.password" autocomplete="new-password" placeholder="至少6位" :disabled="submitting" @update:model-value="revalidateConfirmation" />
            </ElFormItem>
            <ElFormItem label="确认密码" prop="confirmPassword" for="confirm-password">
                <PasswordInput id="confirm-password" v-model="input.confirmPassword" autocomplete="new-password" placeholder="请再次输入密码" :disabled="submitting" />
            </ElFormItem>
            <ElButton class="login-button" type="primary" native-type="submit" :loading="submitting" :disabled="submitting">{{ submitting ? '正在注册' : '注册' }}</ElButton>
        </ElForm>
        <p class="auth-switch">已有账号？<RouterLink :to="{ name: 'login', query: { redirect: route.query.redirect } }">返回登录</RouterLink></p>
    </div>
</template>
