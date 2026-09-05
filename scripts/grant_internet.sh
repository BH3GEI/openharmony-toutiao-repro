#!/bin/sh
# Give com.ss.android.article.news ohos.permission.INTERNET.
#
# OH's appspawn decides via AccessToken, not BMS:
#   AppMgrServiceInner -> AccessTokenKit::VerifyAccessToken(tokenId, INTERNET)
# and when it is not granted it installs a seccomp filter that returns EPERM for
# socket(AF_INET/AF_INET6) -- which is the "MUSL: create socket failed for
# family: 2, errno: 1" in the log.  The adapter's installer maps no Android
# permissions at all, so the app's permission_state_table is empty.
#
# Writing the row directly avoids `bm install -r`, which would recreate the
# bundle directory and wipe the adapter-placed android/base.apk and native libs.
set -e
D=/data/service/el1/public/access_token
PKG=com.ss.android.article.news

TOK=$(sqlite3 "$D/access_token.db" \
      "select token_id from hap_token_info_table where bundle_name='$PKG' limit 1;")
[ -n "$TOK" ] || { echo "  no AccessToken row for $PKG -- is it installed?"; exit 1; }
echo "  token_id=$TOK"

if [ "$(sqlite3 "$D/access_token.db" \
        "select count(*) from permission_state_table where token_id=$TOK and permission_name='ohos.permission.INTERNET';")" = "1" ]; then
    echo "  already granted"; exit 0
fi

[ -d /data/local/tmp/atdb_backup ] || cp -a "$D" /data/local/tmp/atdb_backup
for DB in access_token.db access_token_slave.db; do
    [ -f "$D/$DB" ] || continue
    # grant_state 0 = PERMISSION_GRANTED, grant_flag 4 = SYSTEM_FIXED,
    # device_id PHONE-001, is_general 1 -- same shape as every other app.
    sqlite3 "$D/$DB" "INSERT OR REPLACE INTO permission_state_table
        (token_id,permission_name,device_id,is_general,grant_state,grant_flag) VALUES
        ($TOK,'ohos.permission.INTERNET','PHONE-001',1,0,4),
        ($TOK,'ohos.permission.GET_NETWORK_INFO','PHONE-001',1,0,4);"
done
chown access_token:access_token "$D"/* 2>/dev/null || true
chcon u:object_r:accesstoken_data_file:s0 "$D"/* 2>/dev/null || true

# accesstoken_service reloads the whole db at startup; init respawns it.
PID=$(ps -ef | grep accesstoken_service | grep -v grep | sed 's/  */ /g' | cut -d' ' -f2 | head -1)
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
sleep 6
echo "  granted; accesstoken_service restarted (pid was $PID)"
