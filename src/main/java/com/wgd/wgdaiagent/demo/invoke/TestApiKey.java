package com.wgd.wgdaiagent.demo.invoke;

//测试APIKEY
public interface TestApiKey {

    // APIKey：从环境变量读取，不要硬编码真实密钥（仓库里原来的明文 key 已移除）
    // 本地运行 demo 前先设置环境变量 DASHSCOPE_API_KEY
    String API_KEY = System.getenv("DASHSCOPE_API_KEY");
}
