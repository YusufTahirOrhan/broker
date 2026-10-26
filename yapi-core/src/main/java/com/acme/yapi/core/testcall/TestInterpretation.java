package com.acme.yapi.core.testcall;

import com.acme.yapi.core.response.Outcome;

/** Reading of a test call result, as in the design document's table. */
public final class TestInterpretation {

    private TestInterpretation() {
    }

    public static String of(Outcome outcome, Integer status) {
        if (outcome == Outcome.INVALID_REQUEST) {
            return "Gövde alan tanımına uymuyor; istek gönderilmedi";
        }
        if (outcome == Outcome.TIMEOUT) {
            return "Servis route'un timeout süresi içinde yanıt vermedi";
        }
        if (status == null) {
            return "Servise bağlanılamadı; base URL ya da servisin ayakta olduğu kontrol edilmeli";
        }
        if (status >= 200 && status < 300) {
            return "Çalışıyor";
        }
        if (status >= 300 && status < 400) {
            return "Servis yönlendirme döndü; broker yönlendirmeyi izlemez";
        }
        return switch (status) {
            case 400 -> "Endpoint bulundu ama parametre/body bağlanamadı: isim, tip ya da zorunlu alan sorunu";
            case 401, 403 -> "Servisin güvenliği devrede; test isteğinin kimliği eksik";
            case 404 -> "Path yok ya da controller \"kayıt bulunamadı\" dedi; yoklama geçtiyse ikincisi";
            case 405 -> "Path var, HTTP method yanlış";
            case 406, 415 -> "Body formatı ya da seçilen response tipi servisle uyuşmuyor";
            default -> status >= 500
                    ? "Endpoint'e ulaşıldı, hata iş mantığında (çoğu zaman örnek veriden)"
                    : "Servis isteği reddetti (" + status + ")";
        };
    }
}
