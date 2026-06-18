package com.defi.monitor.macro;

import com.defi.monitor.dto.MacroDtos.MacroEvent;

import java.util.List;

/**
 * Abstração da origem dos eventos macro. Permite alternar entre a fonte real
 * (Forex Factory / faireconomy) e o mock estruturado sem tocar na lógica de
 * janela de 24h do {@link MacroCalendarService}.
 */
public interface MacroCalendarSource {

    /** Retorna os eventos conhecidos (geralmente da semana atual + próxima). */
    List<MacroEvent> fetchEvents();
}
