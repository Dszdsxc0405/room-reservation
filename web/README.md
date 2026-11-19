# Web 工程

Vue 3、Vite 和 TypeScript 的最小入口。当前首页只占位；业务页面和与后端的接口将在对应 BP/AC 任务中实现。

在 `web/` 目录运行：

```sh
npm ci
npm run dev -- --host 127.0.0.1
npm run type-check
npm run lint
npm run build
```

`npm run lint` 只检查；需要自动修复时才使用 `npm run lint:fix`。依赖版本以 `package-lock.json` 为准。
