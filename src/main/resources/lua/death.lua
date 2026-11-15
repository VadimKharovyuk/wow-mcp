-- death.lua — таблица D (метки 777007.5 / 777008.5): смерть, призрак, труп.
-- D[3] время, D[4] мёртв (труп, дух не отпущен), D[5] призрак, D[6]/D[7] координаты трупа (доли 0..1),
-- D[8] задержка воскрешения (с), D[9] зона (континент*100+зона), D[10] боеприпасы (GetInventoryItemCount слота 0; 0 — нет/не стрелок),
-- D[11] счётчик LOOT_OPENED (окно добычи открывалось, даже если автолут закрыл его мгновенно),
-- D[12] опыт на текущем уровне (UnitXP), D[13] опыта до следующего уровня (UnitXPMax),
-- D[14] притворяется мёртвым (бафф «Притвориться мёртвым», 5384): тогда это не смерть, dead = 0.
D = {777007.5, 777008.5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}

local acc = 0
local frame = CreateFrame("Frame")
frame:SetScript("OnUpdate", function(self, elapsed)
  local ok, err = pcall(function()
    D[3] = GetTime()
    acc = acc + elapsed
    if acc < 0.5 then return end
    acc = 0
    local ghost = UnitIsGhost("player") and 1 or 0
    local fdName = GetSpellInfo(5384)
    local fd = (fdName and UnitBuff("player", fdName)) and 1 or 0
    D[14] = fd
    local dead = (UnitIsDead("player") and ghost == 0 and fd == 0) and 1 or 0
    D[10] = GetInventoryItemCount("player", 0) or 0
    D[12] = UnitXP("player") or 0
    D[13] = UnitXPMax("player") or 0
    D[4] = dead
    D[5] = ghost
    if dead == 1 or ghost == 1 then
      SetMapToCurrentZone()
      local cx, cy = GetCorpseMapPosition()
      D[6] = cx or 0
      D[7] = cy or 0
      D[8] = GetCorpseRecoveryDelay() or 0
      D[9] = GetCurrentMapContinent() * 100 + GetCurrentMapZone()
    else
      D[6] = 0
      D[7] = 0
      D[8] = 0
    end
  end)
  if not ok then print("LUA ERR", err) end
end)

local lf = CreateFrame("Frame")
lf:RegisterEvent("LOOT_OPENED")
lf:SetScript("OnEvent", function()
  D[11] = (D[11] or 0) + 1
end)
