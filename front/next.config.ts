import type { NextConfig } from "next";

// 读取服务器配置
const serverConfig = require('./server.config.js');
const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE || serverConfig.api.baseUrl;

const nextConfig: NextConfig = {
  // 将API配置暴露给客户端；隔离环境优先使用 NEXT_PUBLIC_API_BASE
  env: {
    API_BASE_URL: apiBaseUrl,
    APP_NAME: serverConfig.app.name,
    APP_VERSION: serverConfig.app.version,
  },

  // 静态导出配置
  output: 'export',
  // 禁用图片优化（静态导出不支持）
  images: {
    unoptimized: true,
  },
};

export default nextConfig;
