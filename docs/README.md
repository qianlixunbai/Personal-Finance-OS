# Personal Finance OS

> 現代的でエンタープライズグレードの個人金融管理・分析プラットフォーム。

![Version](https://img.shields.io/badge/version-v1.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)
![React](https://img.shields.io/badge/React-19-61DAFB)
![TypeScript](https://img.shields.io/badge/TypeScript-5.x-3178C6)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791)
![License](https://img.shields.io/badge/license-MIT-blue)

---

# 📖 概要

**Personal Finance OS** は、個人の資産、記帳、投資、分析を一か所で管理できるように設計された、エンタープライズグレードの個人金融管理システムです。

従来の家計簿アプリとは異なり、Personal Finance OS は **金融管理、投資追跡、資産分析、長期的な保守性** を重視しています。

このプロジェクトは単なるデモではなく、実際のソフトウェア工学プロジェクトとして、エンタープライズ開発の基準に沿って構築されています。

---

# 🎯 ビジョン

私たちの目的は、単なる家計簿アプリを作ることではありません。

目指しているのは、現代的な **Personal Financial Operating System** を作ることです。

このシステムは次の機能を提供します。

- 個人記帳
- 複数口座管理
- ポートフォリオ管理
- 純資産管理
- 財務分析
- AI による金融インサイト
- 長期的な拡張性

---

# ✨ 主な機能

## 💰 取引台帳

- 収入
- 支出
- 振替
- 返金
- 残高調整

---

## 🏦 口座管理

複数の口座タイプをサポートします。

- 現金
- 銀行口座
- クレジットカード
- 決済サービス
- 証券口座
- 暗号資産ウォレット

---

## 📈 投資ポートフォリオ

以下をサポートします。

- 株式
- ETF
- 投資信託
- 債券
- 金
- 暗号資産
- 現金

追跡項目：

- 保有数量
- 平均取得単価
- 時価
- 損益
- リターン率

---

## 📊 ダッシュボード

以下を視覚的に表示します。

- 総資産
- 純資産
- 月次キャッシュフロー
- 資産配分
- 支出分析
- 投資パフォーマンス

---

## 🤖 AI アシスタント（予定）

将来のバージョンでは、次の AI 機能を提供します。

- 財務レポート
- 支出分析
- 投資インサイト
- 予算提案
- ポートフォリオ分析

AI はコアの業務ロジックを置き換えません。

---

# 🚫 対象外

Personal Finance OS は、以下のシステムではありません。

- 銀行システム
- 株式取引プラットフォーム
- 決済ゲートウェイ
- 定量取引システム
- 証券会社向けプラットフォーム
- 高頻度取引プラットフォーム

このプロジェクトは **個人金融管理** にのみ焦点を当てています。

---

# 🛠 技術スタック

## バックエンド

- Java 21
- Spring Boot 3
- Spring Security
- JWT
- MyBatis-Plus
- Maven

## データベース

- PostgreSQL

## キャッシュ

- Redis（予定）

## フロントエンド

- React
- TypeScript
- Vite
- ECharts

## デプロイ

- Docker

---

# 📂 プロジェクト構成

```text
personal-finance-os/

├── backend/
├── frontend/
├── database/
├── docs/
├── docker/
└── scripts/
```

詳細は `docs` ディレクトリを参照してください。

---

# 📚 ドキュメント

プロジェクトのドキュメントは `docs` 配下に整理されています。

現在の文書には以下が含まれます。

- Project Vision
- Development Guide
- Software Requirements Specification (SRS)
- Architecture Design
- Database Design
- API Specification
- AI Development Rules
- Roadmap
- Architecture Decision Records (ADR)

---

# 🚀 開発方針

このプロジェクトは次の原則に従います。

- Documentation First
- Architecture First
- Code Quality First
- Long-term Maintainability
- Modular Monolith Architecture
- Clean Code
- SOLID Principles
- High Cohesion
- Low Coupling

すべての機能は、既存のアーキテクチャを損なうことなく、システムをより良くするものであるべきです。

---

# 🗺 ロードマップ

## Version 1.0

- User Authentication
- Account Management
- Ledger
- Categories
- Portfolio
- Dashboard
- Analytics

---

## Version 2.0

- Market Data API
- Multi-Currency
- Exchange Rates
- CSV Import / Export
- AI Financial Reports
- Redis Cache

---

## Version 3.0

- Budget Planning
- Financial Goals
- Notifications
- Backup & Restore
- Mobile Optimization

---

## Version 4.0

- Plugin System
- Open API
- Multi-device Synchronization
- Internationalization
- Advanced AI Assistant

---

# 🤝 開発ワークフロー

Requirement

↓

Architecture

↓

Database

↓

API

↓

Implementation

↓

Code Review

↓

Testing

↓

Merge

---

# 📜 ライセンス

このプロジェクトは MIT License で公開予定です。

---

# ❤️ このプロジェクトについて

Personal Finance OS は、単なるポートフォリオではありません。

複数のバージョンを通して継続的に進化し、プロフェッショナルなソフトウェア工学の実践に沿って長期的に育てていくことを目的としたプロジェクトです。

各アーキテクチャ決定、機能実装、コードレビューは、保守性、拡張性、ソフトウェア品質の向上を目的としています。

このリポジトリは、明確なドキュメント、慎重なアーキテクチャ、規律ある開発、継続的改善を通して、チームらしくソフトウェアを作る姿勢そのものを表しています。
