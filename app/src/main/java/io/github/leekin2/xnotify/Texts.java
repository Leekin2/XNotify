package io.github.leekin2.xnotify;

import android.content.Context;
import java.util.Locale;

/** Text XNotify adds to Xiaomi's screens, in the device language (Turkish, English, Russian, Spanish, Chinese). */
final class Texts {
    final String refresh, summary, requesting, alsoHere, never, hintTitle, hintSummary;
    String search, allOff, allOffTitle, allOffMessage, noneOn, cancel;

    private Texts(String refresh, String summary, String requesting, String alsoHere, String never,
            String hintTitle, String hintSummary) {
        this.refresh = refresh;
        this.summary = summary;
        this.requesting = requesting;
        this.alsoHere = alsoHere;
        this.never = never;
        this.hintTitle = hintTitle;
        this.hintSummary = hintSummary;
    }

    private Texts with(String search, String allOff, String allOffTitle, String allOffMessage, String noneOn, String cancel) {
        this.cancel = cancel;
        this.search = search;
        this.allOff = allOff;
        this.allOffTitle = allOffTitle;
        this.allOffMessage = allOffMessage;
        this.noneOn = noneOn;
        return this;
    }

    /** "%1$d apps · last updated %2$s" in the device language. */
    String summary(int count, String when) {
        return String.format(summary, count, when);
    }

    static Texts of(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        switch (locale.getLanguage()) {
            case "tr":
                return new Texts("Telefondan yenile", "%1$d uygulama · son güncelleme %2$s", "Telefondan isteniyor…",
                        "Bu cihazda da kurulu: bildirimler iki kez gelebilir", "henüz yok",
                        "Telefonun uygulama listesi henüz gelmedi",
                        "Sağ üstteki yenile simgesine dokunun ya da bu ekrana birazdan tekrar girin.")
                        .with("Uygulama ara", "Hepsini kapat", "Tüm uygulamalar kapatılsın mı?",
                                "%d uygulamanın bildirimleri artık gelmeyecek.", "Açık uygulama yok", "Vazgeç");
            case "ru":
                return new Texts("Обновить с телефона", "Приложений: %1$d · обновлено %2$s", "Запрос к телефону…",
                        "Установлено и здесь: уведомления могут дублироваться", "ещё нет",
                        "Список приложений телефона ещё не получен",
                        "Нажмите значок обновления вверху справа или откройте этот экран чуть позже.")
                        .with("Поиск приложений", "Выключить все", "Выключить все приложения?",
                                "Уведомления от приложений (%d) больше не будут приходить.", "Нет включённых приложений", "Отмена");
            case "es":
                return new Texts("Actualizar desde el teléfono", "%1$d apps · actualizado %2$s", "Solicitando al teléfono…",
                        "También instalada aquí: las notificaciones pueden duplicarse", "nunca",
                        "La lista de apps del teléfono aún no ha llegado",
                        "Toca el icono de actualizar arriba a la derecha o vuelve a abrir esta pantalla en un momento.")
                        .with("Buscar apps", "Desactivar todas", "¿Desactivar todas las apps?",
                                "Dejarán de llegar las notificaciones de %d apps.", "No hay apps activadas", "Cancelar");
            case "zh":
                return new Texts("从手机刷新", "%1$d 个应用 · 更新于 %2$s", "正在向手机请求…",
                        "此设备也已安装：通知可能重复", "暂无",
                        "尚未收到手机的应用列表",
                        "点按右上角的刷新图标，或稍后重新打开此页面。")
                        .with("搜索应用", "全部关闭", "关闭所有应用？", "将不再接收 %d 个应用的通知。", "没有已开启的应用", "取消");
            default:
                return new Texts("Refresh from phone", "%1$d apps · last updated %2$s", "Requesting from phone…",
                        "Also installed here: notifications may appear twice", "never",
                        "The phone's app list hasn't arrived yet",
                        "Tap the refresh icon at the top right or reopen this screen in a moment.")
                        .with("Search apps", "Turn all off", "Turn off all apps?",
                                "Notifications from %d apps will stop.", "No apps are on", "Cancel");
        }
    }
}
