# Personal Finance OS Frontend

Personal Finance OS のフロントエンドです。

## 技術スタック

- React
- TypeScript
- Vite
- Axios
- React Router

## 開発起動

```powershell
npm install
npm run dev
```

開発サーバーは通常 `http://localhost:5173/` で起動します。

## API 接続

フロントエンドは `/api/v1` を通じてバックエンドへアクセスします。

開発時は `vite.config.ts` の proxy 設定により、`/api` が `http://localhost:8080` に転送されます。

## ビルド

```powershell
npm run build
```
