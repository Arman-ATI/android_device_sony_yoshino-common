package com.sonymobile.customizationselector.Parser;

import android.content.Context;
import android.text.TextUtils;
import com.sonymobile.customizationselector.CSLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 設定 ID（config id）對應的 modem 變體名稱，資料在 assets/modem_conf.json。 */
public class ModemConfParser {

    private static final String TAG = ModemConfParser.class.getSimpleName();

    private static final String ASSET = "modem_conf.json";

    private static Map<String, String> sConf;

    /** 設定 ID 對應的 modem 變體（例如 cht_tw_ims），沒有對應時回傳空字串。 */
    public static String parseModemConf(Context context, String conf) {
        String modemVariant = TextUtils.isEmpty(conf) ? "" : getAll(context).getOrDefault(conf, "");
        CSLog.d(TAG, "Parsed modem: '" + modemVariant + "' for config id: '" + conf + "'");
        return modemVariant;
    }

    /** 所有「設定 ID → modem 變體」。 */
    public static synchronized Map<String, String> getAll(Context context) {
        if (sConf == null) {
            sConf = load(context);
        }
        return sConf;
    }

    private static Map<String, String> load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            Map<String, String> map = new LinkedHashMap<>();
            for (Iterator<String> ids = json.keys(); ids.hasNext(); ) {
                String id = ids.next();
                map.put(id, json.getString(id));
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException | JSONException e) {
            CSLog.e(TAG, "Unable to read " + ASSET, e);
            return Collections.emptyMap();
        }
    }
}
