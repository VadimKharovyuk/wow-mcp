-- combat.lua — таблица A (метки 777011.5 / 777012.5): сколько врагов сейчас бьёт игрока.
-- A[3] время (GetTime, тикает каждый кадр — по нему JS находит таблицу), A[4] число разных врагов, нанёсших удар или промах по игроку
-- за последние 4 секунды (по журналу боя COMBAT_LOG_EVENT_UNFILTERED). Умерший враг и выход из боя убирают записи.
-- A[5] счётчик ошибок Lua этого модуля (JS пишет его рост в журнал, категория lua).
-- Бьющие питомца не считаются: нужно число тех, кто бьёт самого игрока (для щита мстителя и массовых умений).
A = {777011.5, 777012.5, 0, 0, 0}

do
  local WINDOW = 4
  local HOSTILE = COMBATLOG_OBJECT_REACTION_HOSTILE or 64
  local hits = {}
  local hitEvents = {
    SWING_DAMAGE = true, SWING_MISSED = true, RANGE_DAMAGE = true, RANGE_MISSED = true,
    SPELL_DAMAGE = true, SPELL_MISSED = true, SPELL_PERIODIC_DAMAGE = true, SPELL_PERIODIC_MISSED = true,
  }

  local ef = CreateFrame("Frame")
  ef:RegisterEvent("COMBAT_LOG_EVENT_UNFILTERED")
  ef:RegisterEvent("PLAYER_REGEN_ENABLED")
  ef:RegisterEvent("PLAYER_DEAD")
  ef:SetScript("OnEvent", function(self, event, ...)
    local ok, err = pcall(function(...)
      if event ~= "COMBAT_LOG_EVENT_UNFILTERED" then
        for g in pairs(hits) do hits[g] = nil end
        A[4] = 0
        return
      end
      local _, sub, srcGUID, _, srcFlags, dstGUID = ...
      if sub == "UNIT_DIED" or sub == "PARTY_KILL" then
        hits[dstGUID] = nil
        return
      end
      if not hitEvents[sub] then return end
      if dstGUID ~= UnitGUID("player") or srcGUID == dstGUID then return end
      if bit.band(srcFlags or 0, HOSTILE) == 0 then return end
      hits[srcGUID] = GetTime()
    end, ...)
    if not ok then
      A[5] = A[5] + 1
      if A[5] <= 3 then print("LUA ERR combat", err) end
    end
  end)

  local acc = 0
  local function tick(elapsed)
    acc = acc + elapsed
    if acc < 0.2 then return end
    acc = 0
    local now, n = GetTime(), 0
    for g, t in pairs(hits) do
      if now - t > WINDOW then hits[g] = nil else n = n + 1 end
    end
    A[4] = n
  end
  local uf = CreateFrame("Frame")
  uf:SetScript("OnUpdate", function(self, elapsed)
    A[3] = GetTime()
    local ok = pcall(tick, elapsed)
    if not ok then A[5] = A[5] + 1 end
  end)
end
