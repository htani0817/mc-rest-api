#!/bin/bash
#
# MC REST API のスモークテスト（curl だけで動く。jq 不要）
#
# 使い方:
#   ./smoke-test.sh --tokens /opt/minecraft/config/mc_rest_api/tokens.json [--write] [--backup] [--stop]
#   または TOKEN=<admin トークン> ./smoke-test.sh
#
#   既定        : 取得系と、状態を変えないエラー系だけを確認する
#   --write     : 元に戻せる書き込み（ゲームルール・スコア・ファイル・各リスト）も確認する
#                 ※チャットと天候は実際に変わる（天候は clear になる）
#   --backup    : バックアップを 1 つ作って、ダウンロード・削除まで確認する
#   --stop      : 最後にサーバーを停止する
#   --restart   : 最後にサーバーを再起動する（終了コード 75。systemd が立ち上げ直すことを確かめる）
#   --player 名 : オンラインのプレイヤーを使う確認（詳細・持ち物・エフェクト）も行う
#
# 環境変数:
#   BASE  API のベース URL（既定: http://127.0.0.1:8765/api/v1）

set -u

BASE="${BASE:-http://127.0.0.1:8765/api/v1}"
TOKEN="${TOKEN:-}"
READ_TOKEN="${READ_TOKEN:-}"
TOKENS_FILE=""
DO_WRITE=0
DO_BACKUP=0
DO_STOP=0
DO_RESTART=0
PLAYER=""

while [ $# -gt 0 ]; do
    case "$1" in
        --tokens) TOKENS_FILE="$2"; shift 2 ;;
        --write) DO_WRITE=1; shift ;;
        --backup) DO_BACKUP=1; shift ;;
        --stop) DO_STOP=1; shift ;;
        --restart) DO_RESTART=1; shift ;;
        --player) PLAYER="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "不明な引数: $1" >&2; exit 2 ;;
    esac
done

# tokens.json から name に対応するトークンを取り出す
token_of() {
    awk -v want="$1" '
        /"name"/ { gsub(/[",]/, "", $2); name = $2 }
        /"token"/ { gsub(/[",]/, "", $2); if (name == want) { print $2; exit } }
    ' FS=': *' "$TOKENS_FILE"
}

if [ -n "$TOKENS_FILE" ]; then
    [ -r "$TOKENS_FILE" ] || { echo "読めません: $TOKENS_FILE" >&2; exit 2; }
    [ -z "$TOKEN" ] && TOKEN="$(token_of admin)"
    [ -z "$READ_TOKEN" ] && READ_TOKEN="$(token_of read)"
fi
if [ -z "$TOKEN" ]; then
    echo "admin トークンがありません（--tokens か TOKEN= で指定）" >&2
    exit 2
fi

PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# check <説明> <期待ステータス> <メソッド> <パス> [本文] [トークン（none で無し）]
check() {
    local name="$1" expect="$2" method="$3" path="$4" body="${5:-}" tok="${6:-$TOKEN}"
    local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" --max-time 120)
    [ "$tok" != "none" ] && args+=(-H "Authorization: Bearer $tok")
    if [ -n "$body" ]; then
        args+=(-H 'Content-Type: application/json' --data-binary "$body")
    fi
    local code
    code="$(curl "${args[@]}" "$BASE$path")"
    if [ "$code" = "$expect" ]; then
        PASS=$((PASS + 1))
        printf '  OK   %-3s %-6s %-55s %s\n' "$code" "$method" "$path" "$name"
    else
        FAIL=$((FAIL + 1))
        printf '  NG   %-3s %-6s %-55s %s（期待 %s）\n' "$code" "$method" "$path" "$name" "$expect"
        head -c 300 "$TMP/body"; echo
    fi
}

# 直前のレスポンス本文から "key":値 を取り出す（文字列・真偽値・数値）
last_value() {
    sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" "$TMP/body" | head -n 1
}

echo "== 接続先: $BASE"

echo "== 認証"
check "認証なしの health"           200 GET /health "" none
check "トークンなしは 401"          401 GET /server "" none
check "不正なトークンは 401"        401 GET /server "" "invalid-token-invalid-token"
if [ -n "$READ_TOKEN" ]; then
    check "read で取得できる"        200 GET /server "" "$READ_TOKEN"
    check "read でコマンドは 403"    403 POST /console/command '{"command":"list"}' "$READ_TOKEN"
    check "read でファイルは 403"    403 GET /files "" "$READ_TOKEN"
fi
check "無いパスは 404"              404 GET /nothing-here
check "メソッド違いは 405"          405 DELETE /server

echo "== サーバー・性能"
check "サーバー情報"                200 GET /server
check "状態"                        200 GET /server/status
check "性能"                        200 GET /performance
check "OpenAPI"                     200 GET /openapi.json "" none

echo "== 設定・ゲームルール"
check "設定一覧"                    200 GET /settings
check "設定 1 件"                   200 GET /settings/motd
check "無い設定は 404"              404 GET /settings/nope
check "範囲外の値は 400"            400 PATCH /settings '{"view_distance":1}'
check "無いキーは 404"              404 PATCH /settings '{"nope":1}'
check "ゲームルール一覧"            200 GET /gamerules
check "ゲームルール 1 件"           200 GET /gamerules/keep_inventory
check "無いゲームルールは 404"      404 GET /gamerules/nope_rule
check "型違いの値は 400"            400 PUT /gamerules/keep_inventory '{"value":"abc"}'

echo "== プレイヤー・リスト"
check "オンライン一覧"              200 GET /players
check "来たことのある人"            200 GET /players/known
check "オフラインの持ち物は 404"    404 GET /players/Nobody_Smoke_X/inventory
check "OP 一覧"                     200 GET /operators
check "ホワイトリスト"              200 GET /allowlist
check "BAN 一覧"                    200 GET /bans
check "IP BAN 一覧"                 200 GET /ip-bans

echo "== ワールド"
check "ワールド一覧"                200 GET /worlds
check "オーバーワールド"            200 GET /worlds/overworld
check "ネザー"                      200 GET /worlds/minecraft:the_nether
check "無いディメンションは 404"    404 GET /worlds/nope
check "エンティティ"                200 GET "/worlds/overworld/entities?limit=5"
check "不正な種類は 400"            400 GET "/worlds/overworld/entities?type=Bad%20Type"
check "プレイヤーは消せない"        400 DELETE "/worlds/overworld/entities?type=player"

echo "== スコア・データパック・Mod・レジストリ"
check "スコアの目的"                200 GET /scoreboard/objectives
check "チーム"                      200 GET /scoreboard/teams
check "引数なしのスコアは 400"      400 GET /scoreboard/scores
check "データパック"                200 GET /datapacks
check "Mod 一覧"                    200 GET /mods
check "プラグイン"                  200 GET /plugins
check "レジストリ一覧"              200 GET /registries
check "アイテム ID 検索"            200 GET "/registries/item?q=diamond"
check "無いレジストリは 404"        404 GET /registries/nope

echo "== コンソール・チャット・イベント"
check "ログ"                        200 GET "/console/logs?lines=5"
check "チャット履歴"                200 GET /chat/history
check "コマンド実行（list）"        200 POST /console/command '{"command":"list"}'
check "改行入りコマンドは 400"      400 POST /console/command '{"command":"list\nstop"}'
check "SSE チケット"                200 POST /events/ticket
TICKET="$(last_value ticket)"
if [ -n "$TICKET" ]; then
    if curl -s -N --max-time 3 "$BASE/events?ticket=$TICKET&types=server,chat" | grep -q "connected"; then
        PASS=$((PASS + 1)); echo "  OK       GET    /events?ticket=...                                       SSE につながる"
    else
        FAIL=$((FAIL + 1)); echo "  NG       GET    /events?ticket=...                                       SSE につながらない"
    fi
    check "使い終わったチケットは 401" 401 GET "/events?ticket=$TICKET" "" none
fi

echo "== ファイル（読み取りと拒否）"
check "ルート一覧"                  200 GET /files
check "config 一覧"                 200 GET "/files?path=config"
check "root の外は 400"             400 GET "/files?path=../"
check "トークンファイルは 403"      403 GET "/files/content?path=config/mc_rest_api/tokens.json"
check "バックアップ一覧"            200 GET /backups

echo "== 履歴とログ"
check "履歴とログの一覧"            200 GET /history
check "いまのサーバーログ"          200 GET /history/server/latest.log
check "ログ以外の名前は 400"        400 GET "/history/server/server.properties"
check "上の階層は 400"              400 GET "/history/chat/..%2F..%2Fserver.properties"
check "無い種類は 404"              404 GET /history/nope/x.log
[ -n "$READ_TOKEN" ] && check "read では 403" 403 GET /history "" "$READ_TOKEN"

if [ -n "$PLAYER" ]; then
    echo "== オンラインのプレイヤー: $PLAYER"
    check "詳細"                    200 GET "/players/$PLAYER"
    check "持ち物"                  200 GET "/players/$PLAYER/inventory"
    check "エンダーチェスト"        200 GET "/players/$PLAYER/enderchest"
    check "エフェクト"              200 GET "/players/$PLAYER/effects"
    check "統計"                    200 GET "/players/$PLAYER/stats"
    check "進捗"                    200 GET "/players/$PLAYER/advancements"
    if [ "$DO_WRITE" = 1 ]; then
        check "メッセージ"          200 POST "/players/$PLAYER/message" '{"message":"[smoke-test] こんにちは"}'
        check "エフェクト付与"      200 POST "/players/$PLAYER/effects" '{"effect":"minecraft:glowing","seconds":5}'
        check "エフェクト解除"      200 DELETE "/players/$PLAYER/effects?effect=minecraft:glowing"
        check "不正なアイテムは 400" 400 POST "/players/$PLAYER/give" '{"item":"Not An Item"}'
        # 今の状態を読んで、同じ値を入れ直す（見た目は変わらない）
        check "今の状態"            200 GET "/players/$PLAYER"
        MODE="$(last_value gameMode)"
        LEVEL="$(last_value level)"
        check "ゲームモード（同じ値）" 200 PUT "/players/$PLAYER/gamemode" "{\"mode\":\"${MODE:-survival}\"}"
        check "XP レベル（同じ値）" 200 PATCH "/players/$PLAYER/vitals" "{\"xpLevel\":${LEVEL:-0}}"
        check "土を 1 個渡す"       200 POST "/players/$PLAYER/give" '{"item":"minecraft:dirt","count":1}'
        check "土を 1 個消す"       200 POST "/players/$PLAYER/inventory/clear" '{"item":"minecraft:dirt","maxCount":1}'
        check "TP（今いる場所へ）"  200 POST "/players/$PLAYER/teleport" "{\"target\":\"$PLAYER\"}"
    fi
fi

if [ "$DO_WRITE" = 1 ]; then
    echo "== 書き込み（元に戻す）"
    check "現在の keep_inventory"   200 GET /gamerules/keep_inventory
    ORIG="$(last_value value)"
    check "keep_inventory=true"     200 PUT /gamerules/keep_inventory '{"value":true}'
    check "元に戻す"                200 PUT /gamerules/keep_inventory "{\"value\":${ORIG:-false}}"

    check "目的を追加"              201 POST /scoreboard/objectives '{"name":"mc_smoke","criteria":"dummy","displayName":"スモーク"}'
    check "スコアを設定"            200 PUT /scoreboard/scores/mc_smoke/smoke_holder '{"score":42}'
    check "スコアを取得"            200 GET "/scoreboard/scores?objective=mc_smoke"
    check "スコアを消す"            200 DELETE /scoreboard/scores/mc_smoke/smoke_holder
    check "目的を消す"              200 DELETE /scoreboard/objectives/mc_smoke

    check "時刻を 0 進める"         200 PUT /worlds/overworld/time '{"add":0}'
    check "天候を晴れに"            200 PUT /worlds/overworld/weather '{"type":"clear"}'
    check "不正な天候は 400"        400 PUT /worlds/overworld/weather '{"type":"snow"}'
    check "全体メッセージ"          200 POST /server/broadcast '{"message":"[smoke-test] broadcast"}'
    check "チャット送信"            200 POST /chat '{"message":"[smoke-test] chat","sender":"smoke"}'
    check "今日のチャット履歴"      200 GET "/history/chat/chat-$(date +%F).log"
    grep -q "smoke-test" "$TMP/body" || { FAIL=$((FAIL + 1)); echo "  NG   今日のチャット履歴にさっきの発言がない"; }
    check "今日の操作の記録"        200 GET "/history/audit/audit-$(date +%F).log"
    check "保存"                    200 POST /server/save '{"flush":false}'
    check "再起動の秒数が範囲外は 400" 400 POST /server/restart '{"delaySeconds":601}'
    check "10 分後に再起動を予告"   200 POST /server/restart '{"delaySeconds":600,"message":"smoke-test"}'
    check "予告中は 409"            409 POST /server/restart '{"delaySeconds":600}'
    check "状態に予告時刻が出る"    200 GET /server/status
    grep -q '"restartAt":"' "$TMP/body" || { FAIL=$((FAIL + 1)); echo "  NG   状態に restartAt が出ていない"; }
    check "再起動を取り消す"        200 DELETE /server/restart
    check "取り消し済みは 404"      404 DELETE /server/restart
    [ -n "$READ_TOKEN" ] && check "read で再起動は 403" 403 POST /server/restart '{}' "$READ_TOKEN"

    TEST_UUID="00000000-0000-4000-8000-00000000abcd"
    check "ホワイトリストに追加"    201 POST /allowlist "{\"player\":\"$TEST_UUID\"}"
    check "ホワイトリストから外す"  200 DELETE "/allowlist/$TEST_UUID"
    check "BAN を追加"              201 POST /bans "{\"player\":\"$TEST_UUID\",\"reason\":\"smoke\",\"expires\":\"2099-01-01T00:00:00Z\"}"
    check "BAN を外す"              200 DELETE "/bans/$TEST_UUID"
    check "BAN されていなければ 404" 404 DELETE "/bans/$TEST_UUID"
    check "IP BAN を追加"           201 POST /ip-bans '{"ip":"203.0.113.250","reason":"smoke"}'
    check "IP BAN を外す"           200 DELETE /ip-bans/203.0.113.250
    check "不正な IP は 400"        400 POST /ip-bans '{"ip":"999.1.1.1"}'
    check "OP に追加"               201 POST /operators "{\"player\":\"$TEST_UUID\",\"level\":2}"
    check "OP から外す"             200 DELETE "/operators/$TEST_UUID"

    check "ディレクトリ作成"        201 POST /files/mkdir '{"path":"mc-smoke-test"}'
    printf 'hello\n' > "$TMP/up.txt"
    code="$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $TOKEN" \
        --data-binary @"$TMP/up.txt" "$BASE/files/content?path=mc-smoke-test/a.txt")"
    if [ "$code" = 201 ]; then PASS=$((PASS + 1)); echo "  OK   201 PUT    /files/content?path=mc-smoke-test/a.txt               アップロード"
    else FAIL=$((FAIL + 1)); echo "  NG   $code PUT    /files/content?path=mc-smoke-test/a.txt               アップロード（期待 201）"; fi
    check "ダウンロード"            200 GET "/files/content?path=mc-smoke-test/a.txt"
    grep -q hello "$TMP/body" || { FAIL=$((FAIL + 1)); echo "  NG   ダウンロードした中身が違う"; }
    check "上書き禁止なら 409"      409 PUT "/files/content?path=mc-smoke-test/a.txt&overwrite=false" "x"
    check "コピー"                  201 POST /files/copy '{"from":"mc-smoke-test/a.txt","to":"mc-smoke-test/b.txt"}'
    check "移動"                    200 POST /files/move '{"from":"mc-smoke-test/b.txt","to":"mc-smoke-test/c.txt"}'
    check "中身ありは recursive 必須" 409 DELETE "/files?path=mc-smoke-test"
    check "ディレクトリ削除"        200 DELETE "/files?path=mc-smoke-test&recursive=true"
    check "config ごと消すのは 403" 403 DELETE "/files?path=config&recursive=true"
fi

if [ "$DO_BACKUP" = 1 ]; then
    echo "== バックアップ"
    check "作成開始"                202 POST /backups
    JOB="$(last_value id)"
    STATE=""
    for _ in $(seq 1 300); do
        curl -s -H "Authorization: Bearer $TOKEN" "$BASE/backups/jobs/$JOB" > "$TMP/body"
        STATE="$(last_value state)"
        [ "$STATE" != "running" ] && break
        sleep 1
    done
    NAME="$(last_value file)"
    if [ "$STATE" = "done" ]; then PASS=$((PASS + 1)); echo "  OK       バックアップ完了: $NAME"
    else FAIL=$((FAIL + 1)); echo "  NG       バックアップの状態: $STATE"; cat "$TMP/body"; echo; fi
    if [ -n "$NAME" ]; then
        check "ダウンロード"        200 GET "/backups/$NAME"
        check "削除"                200 DELETE "/backups/$NAME"
    fi
fi

if [ "$DO_RESTART" = 1 ]; then
    echo "== 再起動"
    check "すぐ再起動"              200 POST /server/restart '{"delaySeconds":0}'
elif [ "$DO_STOP" = 1 ]; then
    echo "== 停止"
    check "サーバー停止"            200 POST /server/stop
fi

echo
echo "結果: OK $PASS / NG $FAIL"
[ "$FAIL" = 0 ]
