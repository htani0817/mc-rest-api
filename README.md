# MC REST API

Minecraft サーバーの情報取得と操作を、REST API（JSON）で行える NeoForge 26.2 のサーバー専用 Mod です。管理画面や Bot のバックエンドに使う前提で作りました。

- 対象: Minecraft 26.2 / NeoForge 26.2.0.88 以降（Youer のようなハイブリッドサーバーでも動きます）
- 入れる場所: サーバーだけ（クライアントには要りません）
- エンドポイント: 95 個。仕様は [openapi.json](src/main/resources/openapi.json)（起動中は `GET /api/v1/openapi.json` でも取れます）
- 既定では `127.0.0.1:8765` だけで待ち受けます

## できること

| 区分 | 主なエンドポイント | 権限 |
| --- | --- | --- |
| サーバー | `GET /server`、`GET /server/status`、`GET /performance`、`POST /server/save`、`POST /server/broadcast`、`POST /server/reload`、`POST /server/stop`、`POST /server/restart`、`DELETE /server/restart` | read / write / admin |
| 設定 | `GET /settings`、`PATCH /settings`（20 項目。server.properties にも書き戻ります） | read / write |
| ゲームルール | `GET /gamerules`、`PUT /gamerules/{name}` | read / write |
| プレイヤー | 一覧・詳細・持ち物・エンダーチェスト・統計・進捗、give・clear・キック・TP・ゲームモード・体力/満腹度/XP・エフェクト・個別メッセージ | read / write |
| OP・ホワイトリスト・BAN | `/operators`、`/allowlist`、`/bans`、`/ip-bans` の取得・追加・削除 | read / write / admin |
| ワールド | ディメンション情報、時刻・天候・スポーン・ワールドボーダー、エンティティの一覧と除去、ブロックの取得と設置 | read / write |
| スコア・データ | スコアボード、チーム、データパックの有効/無効、Mod 一覧、Bukkit プラグイン一覧、レジストリ ID 一覧 | read / write / admin |
| コンソール | `POST /console/command`（任意コマンド）、`GET /console/logs` | admin |
| チャット・イベント | `GET /chat/history`、`POST /chat`、`GET /events`（SSE） | read / write |
| ファイル | サーバーディレクトリ内の一覧・ダウンロード・アップロード・移動・コピー・削除 | admin |
| バックアップ | ワールドを zip にする（非同期）、一覧・ダウンロード・削除 | admin |
| 履歴 | チャット・出来事・操作の記録・サーバーログ（直近 7 日分）の一覧とダウンロード | admin |

応答の形:

```json
{ "data": { ... } }
{ "error": { "code": "player_offline", "message": "オンラインのプレイヤーにいません: Steve" } }
```

- `{player}` は名前でも UUID でもかまいません
- `{dim}` は `overworld` / `the_nether` / `the_end` / `minecraft:xxx`
- 書き込み系の多く（TP・give・天候など）は、中でバニラのコマンドを組み立てて実行します。応答の `output` にコマンドの出力が入ります

## 導入

1. [Releases](https://github.com/htani0817/mc-rest-api/releases/latest) から `mc-rest-api-<版>+mc26.2-neoforge.jar` をダウンロードし、サーバーの `mods/` に置きます
2. サーバーを起動（再起動）します
3. ログに次の 2 行が出れば動いています

   ```text
   [MC REST API] トークンを生成しました（admin / write / read）: <サーバー>/config/mc_rest_api/tokens.json
   [MC REST API] http://127.0.0.1:8765/api/v1 で待ち受けています（エンドポイント 95 個）
   ```

4. サーバー上で試します

   ```bash
   curl http://127.0.0.1:8765/api/v1/health
   TOKEN=$(grep -A1 '"admin"' config/mc_rest_api/tokens.json | grep token | cut -d'"' -f4)
   curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/api/v1/server/status
   ```

更新するときは、古い jar を消してから新しい jar を置きます（同じ Mod の jar が 2 つあると起動しません）。

## 設定

初回起動で `config/mc_rest_api/` に 2 つのファイルができます。変えたらサーバーを再起動してください。

### config.json

| 項目 | 既定値 | 内容 |
| --- | --- | --- |
| `bindAddress` | `127.0.0.1` | 待ち受けアドレス。外から直接つなぐなら `0.0.0.0`（その場合は HTTPS のリバースプロキシを前に置く） |
| `port` | `8765` | 待ち受けポート |
| `corsAllowedOrigins` | `[]` | ブラウザから直接呼ぶときに許可するオリジン（例: `http://localhost:5173`） |
| `maxJsonBytes` | 1 MB | JSON 本文の上限 |
| `maxUploadBytes` | 64 MB | ファイルアップロードの上限 |
| `filesRoot` | `.` | ファイル管理 API で扱える範囲（サーバーディレクトリからの相対） |
| `backupDir` | `backups` | バックアップの保存先 |
| `backupKeep` | `10` | 残すバックアップの数（0 以下で自動削除しない） |
| `logBufferLines` | `2000` | `/console/logs` で持つ行数 |
| `chatHistorySize` | `500` | `/chat/history` で持つ件数 |
| `serverTaskTimeoutSeconds` | `10` | サーバースレッドの処理を待つ秒数 |
| `maxSseClients` | `32` | SSE の同時接続数 |
| `authFailureLimit` / `authBlockSeconds` | `10` / `300` | 1 分間に認証を 10 回失敗した IP を 5 分拒否する |
| `historyKeepDays` | `7` | 履歴とサーバーログを残す日数 |
| `restartExitCode` | `75` | `POST /server/restart` で Java を終わらせるときの終了コード |

### tokens.json

```json
{
  "tokens": [
    { "name": "admin", "token": "...", "scope": "admin" },
    { "name": "write", "token": "...", "scope": "write" },
    { "name": "read",  "token": "...", "scope": "read" }
  ]
}
```

- トークンは 64 桁の乱数で、Linux では所有者だけが読める権限（600）で作ります
- 本数・名前・値は自由に変えられます（値は 16 文字以上）。名前は操作の記録に出ます
- 権限は `read`（取得だけ）< `write`（ゲーム内の操作）< `admin`（任意コマンド・ファイル・バックアップ・停止・OP）
- ファイル管理 API からは tokens.json を読めない・消せないようにしています

> [!WARNING]
> admin トークンはサーバーの root と同じ重さです。ファイル管理で `mods/` に jar を置けますし、任意のコマンドも打てます。サーバー側のプログラムにだけ持たせ、ブラウザには渡さないでください。

## 使い方の例

```bash
API=http://127.0.0.1:8765/api/v1
H="Authorization: Bearer $TOKEN"

curl -H "$H" $API/players
curl -H "$H" -X PATCH $API/settings -H 'Content-Type: application/json' -d '{"motd":"今日も平和","max_players":30}'
curl -H "$H" -X PUT $API/gamerules/keep_inventory -d '{"value":true}'
curl -H "$H" -X POST $API/players/Steve/give -d '{"item":"minecraft:diamond","count":5}'
curl -H "$H" -X PUT $API/worlds/overworld/time -d '{"marker":"minecraft:day"}'
curl -H "$H" -X POST $API/console/command -d '{"command":"list"}'
curl -H "$H" -X POST $API/backups
```

### リアルタイム配信（SSE）

ブラウザの EventSource はヘッダーを付けられないので、先にチケット（30 秒・1 回限り）をもらって `?ticket=` でつなぎます。

```js
const { data } = await fetch(`${API}/events/ticket`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } }).then(r => r.json());
const es = new EventSource(`${API}/events?ticket=${data.ticket}&types=chat,join,leave,death`);
es.addEventListener('chat', e => console.log(JSON.parse(e.data)));
```

イベントの種類は `log`（admin のみ）・`chat`・`join`・`leave`・`death`・`advancement`・`command`・`server` です。15 秒ごとに keep-alive のコメント行が流れます。ファイルとバックアップのダウンロードリンクも、同じチケットで開けます。

### 再起動

`POST /server/restart` は、予告してから保存して止め、終了コード `restartExitCode`（既定 75）で Java を終わらせます。立ち上げ直すのは起動した側（systemd の `Restart=on-failure` など）です。`POST /server/stop` は終了コード 0 で終わるので、起動し直しません。

```bash
# 30 秒前から予告して再起動（チャットで 30・10・5〜1 秒前に知らせる）
curl -H "$H" -X POST $API/server/restart -d '{"delaySeconds":30,"message":"Mod の更新"}'
# 予告中なら取り消す
curl -H "$H" -X DELETE $API/server/restart
```

- `delaySeconds` は 0〜600（既定 10）。予告中にもう一度送ると 409 です。予告中の時刻は `GET /server/status` の `restartAt` に出ます
- 予告中に `POST /server/stop` が来たら、再起動はやめて止まったままにします
- tmux の中で java を動かしている場合は、java が終わっても systemd が気づかないので起動し直されません。java を systemd から直接動かしてください（[mc-server-manager](https://github.com/htani0817/mc-server-manager) の `deploy/minecraft.service` が例です）

### バックアップと復元

`POST /backups` で、自動保存を止める → 全ワールドを保存 → `world` を zip にする → 自動保存を元に戻す、を順に行います。進み具合は `GET /backups/jobs/{id}` の `state`（running / done / failed）で見られます。保存先は `backups/world-YYYYMMDD-HHmmss.zip` です。

復元はサーバーが止まっているときにしかできないので、API には入れていません。手で行います。

```bash
# サーバーを止めてから
mv world world.old-$(date +%Y%m%d%H%M%S)
unzip backups/world-20261031-223320.zip
# サーバーを起動する
```

## 外から呼ぶとき

API を使うプログラムを同じサーバーに置き、API は `127.0.0.1` のままにするのが一番安全です。どうしても外から直接呼ぶなら、HTTPS のリバースプロキシを前に置いてください。

```nginx
location /api/v1/ {
    proxy_pass http://127.0.0.1:8765;
    proxy_set_header X-Forwarded-For $remote_addr;
    # SSE を詰まらせない
    proxy_buffering off;
    proxy_read_timeout 1h;
}
```

リバースプロキシ経由だと、API から見た接続元はすべて 127.0.0.1 になります。認証失敗による締め出しはプロキシ側（fail2ban など）で行ってください。

## 記録とログ

| 種類 | 場所 | 中身 |
| --- | --- | --- |
| チャット | `logs/mc-rest-api/chat/chat-YYYY-MM-DD.log` | 1 行 1 件の JSON。再起動後の `/chat/history` はここから読み戻す |
| 出来事 | `logs/mc-rest-api/events/events-YYYY-MM-DD.log` | 参加・退出・死亡・進捗・コマンド・起動/停止 |
| 操作の記録 | `logs/mc-rest-api/audit/audit-YYYY-MM-DD.log` | 書き込み系 API の記録（時刻・IP・トークン名・メソッド・パス・結果） |
| サーバーログ | `logs/YYYY-MM-DD-N.log.gz` | Minecraft が作るもの。`latest.log` と `debug*` は消さない |

どれも直近 7 日分（`historyKeepDays`）だけ残し、古いものは起動時と 1 時間ごとに消えます。

## 動作確認用のスクリプト

[tools/smoke-test.sh](tools/smoke-test.sh) で、全エンドポイントを curl で叩けます（jq は要りません）。

```bash
# 取得系と、状態を変えないエラー系だけ
bash tools/smoke-test.sh --tokens config/mc_rest_api/tokens.json

# 元に戻せる書き込みとバックアップも（天候は clear になり、チャットにテスト文が出る）
bash tools/smoke-test.sh --tokens config/mc_rest_api/tokens.json --write --backup

# ログイン中のプレイヤーを使う確認も
bash tools/smoke-test.sh --tokens config/mc_rest_api/tokens.json --write --player <プレイヤー名>
```

最後に `結果: OK n / NG 0` と出れば、全部通っています。

## 既知の制約

- 持ち物・エンダーチェスト・エフェクトはオンラインのプレイヤーだけです（オフラインは 404）。統計と進捗はオフラインでも取れます
- API から実行したコマンドは、バニラのコンソールと同じく OP のチャットにも通知されます
- Youer など Paper 系のサーバーは難易度をワールドごとに持ちます。設定の `difficulty` はオーバーワールドの値を返し、変えるときは全ワールドに同じ値を入れます
- 26.2 で時刻は「ワールドクロック」方式に変わっています。`clockTime` はそのディメンションの既定の時計の値です
- ファイル管理は `..` とシンボリックリンクのどちらでも、`filesRoot` の外には出られません

## ビルド

```bash
./gradlew build      # Windows は gradlew.bat build
```

- Gradle を動かす JDK は 17 以上なら何でもかまいません。Java 25 は Gradle が自動で取ってきます
- できた jar は `dist/` にコピーされます
- ビルド出力は `~/.gradle/project-builds/mc-rest-api/` に置きます（プロジェクトのパスに日本語が入っていても動くように）。場所を変えるときは `-PmcBuildRoot=<パス>` を付けます
- 開発用サーバーは `./gradlew runServer` です

## ライセンス

[MIT](LICENSE)
