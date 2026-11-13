# VaultBank

VaultBank 是一款專為 Paper 伺服器設計的經濟插件，可以直接取代 TheosisEconomy。除了一般的銀行餘額、轉帳與排行榜，還加入了中世紀風格的**實體貨幣**（硬幣與鈔票）和**不記名支票**：玩家可以把餘額提領成物品拿去交易、放進箱子、賣給商店，再到銀行存回來。每一枚貨幣都有伺服器簽章，玩家無法偽造。

## 功能特性

* **完整的經濟插件**：
  * 透過 Vault 提供經濟服務，商店、拍賣、在線獎勵等使用 Vault 的插件不用改任何設定。
  * 餘額支援小數（預設 2 位）、上限、起始金額；轉帳可設最低金額與手續費。
  * 第一次啟動時自動匯入 TheosisEconomy 的所有帳戶（只讀不改原資料，只匯入一次）。
* **實體貨幣**：
  * 硬幣：1 元銅幣、5 元鐵幣、10 元銀幣、50 元金幣；鈔票：100 元綠鈔、500 元藍鈔、1000 元紫鈔。
  * 同面額的每一枚完全相同，可以堆疊，村民與交易插件（例如 Shopkeepers）都視為同一個物品。
  * 提取時可以讓**系統自動配**（最大面額優先），也可以**自己選面額與數量**，背包放不下會直接提示。
* **不記名支票**：
  * 從餘額開出任意整數金額的支票，任何人拿到都能到銀行兌現。
  * 每張支票有唯一編號，同一張只能兌現一次，複製出來的支票會被拒絕。
* **防偽與防複製**：
  * 貨幣與支票帶有伺服器密鑰的簽章，改名、套外觀、手動加標籤都做不出能存入的貨幣。
  * 記錄每種面額的發行量與回收量；回收超過發行（代表有人利用漏洞複製）時，自動通知管理員。
  * 存提時依「不會兩邊都有」的順序存檔，伺服器當機也不會變成錢與物品各留一份。
* **3D 外觀材質包**：
  * Java 版：貨幣、鈔票與支票都是有厚度的 3D 模型，經 [HttpHub](https://github.com/clre20/HttpHub) 以獨立的材質包發送，不會蓋掉其他插件的材質包。
  * 基岩版：自動產生 `.mcpack` 與 Geyser 自訂物品對照檔，放進 Geyser 資料夾。
  * 貼圖放在 `plugins/VaultBank/textures/`，換成自己的 PNG 後 `/vb reload` 即可。
* **箱子介面**：
  * `/vb` 打開銀行：存入、提取、開支票、轉帳、交易紀錄、收款開關，並顯示自己的餘額與排名。
  * 介面能做的事都有對應的指令。
  * 基岩版玩家（Geyser／Floodgate）的介面也有貨幣圖示；要輸入金額時跳出基岩版輸入框，不用聊天室。
* **交易紀錄**：
  * 存提、轉帳、支票、管理員操作、其他插件透過 Vault 的存提（會記下是哪個插件）都有紀錄，預設保留 30 天後自動刪除。
* **效能**：
  * 所有餘額放在記憶體，變動只標記，每 5 秒批次寫入 SQLite；資料庫只用一條背景執行緒。
  * 其他插件可以從任何執行緒呼叫 Vault，不會算錯或卡住主執行緒。
* **所有文字都可自訂**：
  * 聊天訊息、介面、物品名稱與描述、後台訊息全部在 `messages.yml`。
  * `config.yml` 與 `messages.yml` 缺少的項目會在啟動與 `/vb reload` 時自動補上，保留原本的值。
* **版本檢查**：
  * 啟動時在背景向 GitHub Releases 檢查一次，也可以用 `/vb version` 手動檢查；結果快取 10 分鐘，不會定時輪詢。
  * 有新版本時，具有 `vaultbank.version` 權限的管理員登入 3 秒後會收到提醒；兩項功能都能在 `config.yml` 關閉。

## 指令與權限

本插件主指令為 `/vaultbank`（別名 `/vb`）。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/vb` 或 `/vb menu` | 打開銀行選單 | 遊戲內 | `vaultbank.use` | 所有人 |
| `/vb help` | 顯示指令說明 | 遊戲內 / 控制台 | 無 | 所有人 |
| `/vb balance [玩家]`、`/money [玩家]` | 查看自己（或他人）的餘額與排名 | 遊戲內 / 控制台 | `vaultbank.balance`（他人：`vaultbank.balance.others`） | 所有人（他人：OP） |
| `/vb pay <玩家> <金額>`、`/pay` | 轉帳（可轉給離線玩家） | 遊戲內 | `vaultbank.pay` | 所有人 |
| `/vb paytoggle` | 開關是否接受別人轉帳 | 遊戲內 | `vaultbank.paytoggle` | 所有人 |
| `/vb baltop [頁]`、`/baltop` | 餘額排行榜（玩家只看得到自己的排名，在 `/money` 與 `/vb` 選單） | 遊戲內 / 控制台 | `vaultbank.baltop` | OP |
| `/vb deposit [hand\|all]` | 存入手上或背包裡的貨幣與支票；不加參數開啟存入介面 | 遊戲內 | `vaultbank.deposit` | 所有人 |
| `/vb withdraw [金額]` | 系統自動配面額提取；不加參數開啟提取介面 | 遊戲內 | `vaultbank.withdraw` | 所有人 |
| `/vb withdraw <面額> <數量>` | 指定面額提取，例如 `/vb withdraw 100 5` | 遊戲內 | `vaultbank.withdraw` | 所有人 |
| `/vb cheque [金額]` | 開支票；不加金額時在聊天室輸入 | 遊戲內 | `vaultbank.cheque` | 所有人 |
| `/vb log [頁]` | 自己的交易紀錄 | 遊戲內 | `vaultbank.log` | 所有人 |
| `/vb log <玩家> [頁]` | 他人的交易紀錄 | 遊戲內 / 控制台 | `vaultbank.log.others` | OP |
| `/vb eco <give\|take\|set\|reset> <玩家> [金額]`、`/eco` | 管理玩家餘額 | 遊戲內 / 控制台 | `vaultbank.admin.eco` | OP |
| `/vb give <玩家> <面額> [數量]` | 直接發放實體貨幣（不扣款） | 遊戲內 / 控制台 | `vaultbank.admin.give` | OP |
| `/vb audit` | 貨幣流通量、未兌現支票、全服餘額統計 | 遊戲內 / 控制台 | `vaultbank.admin.audit` | OP |
| `/vb reload` | 重新載入設定、文字與材質包 | 遊戲內 / 控制台 | `vaultbank.reload` | OP |
| `/vb version` | 查看版本並檢查更新，有新版時登入會收到提醒 | 遊戲內 / 控制台 | `vaultbank.version` | OP |

> 有 `vaultbank.admin.alert` 的人會收到偽造貨幣、重複兌現支票、流通量異常的警告。`vaultbank.player` 包含所有玩家權限，`vaultbank.admin` 包含所有管理員權限，`vaultbank.*` 包含全部。

## 安裝與設定

1. 需要 [Vault](https://www.spigotmc.org/resources/vault.34315/)。要發送 Java 版材質包請安裝 [HttpHub](https://github.com/clre20/HttpHub)；基岩版玩家需要 Geyser。
2. **從 TheosisEconomy 換過來**：關服 → 移除 TheosisEconomy 的 jar（`plugins/TheosisEconomy/` 資料夾留著）→ 放入 VaultBank → 開服。後台會顯示匯入了幾個帳戶與總額。TheosisEconomy 還裝著的話，VaultBank 不會接手經濟並在後台提示。
3. 如果有 EssentialsX，請關閉它的 `/money`、`/eco`、`/baltop`、`/pay`，或在 `commands.yml` 把這些指令指到 VaultBank。
4. 基岩版：第一次啟動後重開一次伺服器，Geyser 才會載入 VaultBank 的材質包與自訂物品。

| 設定 | 說明 |
| :--- | :--- |
| `currency.decimal-places` / `default-balance` / `max-balance` | 小數位數、起始金額、餘額上限 |
| `denominations.*` | 個別面額是否可以提取 |
| `storage.save-interval-seconds` | 餘額批次寫入的間隔 |
| `baltop.*` | 排行榜更新間隔、每頁人數、最低上榜金額 |
| `pay.min-amount` / `fee-percent` | 最低轉帳金額、轉帳手續費 |
| `withdraw.max-items` | 一次最多提取幾枚／張 |
| `cheque.min-amount` / `max-amount` / `fee-percent` | 支票金額上下限、手續費 |
| `interest.*` | 利息（預設關閉）：利率、間隔、上限、是否只發給在線玩家 |
| `logs.*` | 交易紀錄開關、保留天數、是否記錄其他插件的存提 |
| `anti-counterfeit.alert-admins` / `confiscate-invalid` | 是否通知管理員、偽造品是否沒收 |
| `resource-pack.java.*` | 材質包開關、是否強制、沒有 HttpHub 時的自訂網址 |
| `resource-pack.bedrock.*` | 是否自動放進 Geyser、Geyser 資料夾位置 |
| `resource-pack.model.*` | 3D 模型厚度 |
| `gui.chat-input-timeout-seconds` / `action-cooldown-ms` / `bedrock-open-delay-ticks` | 聊天輸入逾時、操作最短間隔、基岩版切換介面的間隔 |
| `migration.import-theosis-economy` | 第一次啟動是否匯入 TheosisEconomy |
| `update-checker.*` | 啟動時檢查更新、登入提醒 |

## 防偽說明

* 密鑰在 `plugins/VaultBank/secret.key`，第一次啟動自動產生，**請勿外流**。刪掉它重開會產生新密鑰，所有已發出的貨幣與支票都會失效。
* 創造模式複製出來的貨幣簽章有效，這只有創造模式的管理員做得到；`/vb audit` 的流通量會顯示異常。

---

# VaultBank

VaultBank is an economy plugin for Paper and a drop-in replacement for TheosisEconomy. Besides bank balances, payments and a leaderboard, it adds medieval-style **physical money** (coins and banknotes) and **bearer cheques**: players can withdraw their balance as items, trade them, store them in chests or sell them to shops, then deposit them back at the bank. Every coin carries a server signature, so players cannot forge money.

## Features

* **Full economy plugin**: provides Vault economy, so shops and other Vault plugins work unchanged; decimal balances (2 places by default), balance cap, starting balance, minimum payment and payment fee; imports every TheosisEconomy account on first start (read-only, once).
* **Physical money**: coins of 1 (copper), 5 (iron), 10 (silver) and 50 (gold); banknotes of 100 (green), 500 (blue) and 1000 (purple). Coins of the same value are identical and stack, so villagers and trading plugins treat them as the same item. Withdraw by letting the teller split the amount automatically, or pick denominations yourself.
* **Bearer cheques**: any whole amount; each has a unique serial and can be cashed only once.
* **Anti-counterfeit**: items are signed with a server key; minted and redeemed counts per denomination are tracked and admins are alerted when more is redeemed than was minted; saves are ordered so a crash never leaves both the money and the items.
* **3D resource pack**: Java models with thickness, delivered as a separate pack through [HttpHub](https://github.com/clre20/HttpHub); a Bedrock `.mcpack` and Geyser custom item mappings are generated and installed automatically. Textures can be replaced in `plugins/VaultBank/textures/`.
* **Chest GUI**: `/vb` opens the bank (deposit, withdraw, cheque, pay, history, payment toggle) and shows your balance and rank; the full leaderboard `/baltop` is admin-only; every GUI action also has a command.
* **Transaction history**: kept 30 days by default, including Vault deposits and withdrawals by other plugins (with the plugin name).
* **Performance**: balances live in memory and are written to SQLite in batches every 5 seconds on one background thread; Vault calls are safe from any thread.
* **Customizable text** in `messages.yml`; missing keys in both files are filled in automatically.
* **Update checker**: checks GitHub Releases on startup and with `/vb version`, cached 10 minutes; admins with `vaultbank.version` are reminded 3 seconds after joining.

## Commands & Permissions

The main command is `/vaultbank` (alias `/vb`). See the table above; `/money`, `/pay`, `/baltop` and `/eco` are shortcuts. `vaultbank.player` grants all player permissions, `vaultbank.admin` all admin permissions.

## Installation

1. Requires Vault. Install HttpHub to send the Java resource pack, and Geyser for Bedrock players.
2. Switching from TheosisEconomy: stop the server, remove the TheosisEconomy jar (keep its folder), add VaultBank and start. Balances are imported automatically.
3. If you use EssentialsX, disable its `/money`, `/eco`, `/baltop` and `/pay`.
4. Bedrock: restart once after the first start so Geyser loads the pack and custom items.
