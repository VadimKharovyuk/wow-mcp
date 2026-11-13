-- spells.lua — таблица заклинаний на панелях (S) + события применения.
local function H(n)
  local h = 0
  for i = 1, #n do h = (h * 31 + n:byte(i)) % 2147483647 end
  return h
end

-- глобальная таблица S (метки 777003.5 / 777004.5)
S = {777003.5, 777004.5, 0, 0, 0, 0}
-- расширим при скане

local function keyNumbers(bind)
  -- возвращает keyCode, mods
  if not bind or bind == "" then return 0, 0 end
  local mods = 0
  local base = bind
  -- разбираем модификаторы
  while true do
    local changed = false
    if base:match("^SHIFT%-") then base = base:sub(7); mods = mods + 1; changed = true end
    if base:match("^CTRL%-") then base = base:sub(6); mods = mods + 2; changed = true end
    if base:match("^ALT%-") then base = base:sub(5); mods = mods + 4; changed = true end
    if not changed then break end
  end
  local code = 0
  if #base == 1 then
    local b = base:byte(1)
    if (b >= 65 and b <= 90) or (b >= 48 and b <= 57) then code = b end
    if base == "-" then code = 189 end
    if base == "=" then code = 187 end
  else
    local n = tonumber(base:match("^F(%d+)$"))
    if n and n >= 1 and n <= 12 then code = 111 + n end
  end
  return code, mods
end

local function spellIdFromSlot(slot)
  local t, id = GetActionInfo(slot)
  if t ~= "spell" then return nil, nil end
  local name = GetSpellName(id, "spell")
  if not name or name == "" then return nil, nil end
  local link = GetSpellLink(id, "spell")
  if not link then link = GetSpellLink(name) end
  local sid = 0
  if link then sid = tonumber(link:match("spell:(%d+)")) or 0 end
  return name, sid
end

local slotsToScan = {}
-- заполняем список слотов
for i = 1, 12 do slotsToScan[#slotsToScan+1] = {slot=i, cmd="ACTIONBUTTON"..i} end
for i = 61, 72 do slotsToScan[#slotsToScan+1] = {slot=i, cmd="MULTIACTIONBAR1BUTTON"..(i-60)} end
for i = 49, 60 do slotsToScan[#slotsToScan+1] = {slot=i, cmd="MULTIACTIONBAR2BUTTON"..(i-48)} end
for i = 25, 36 do slotsToScan[#slotsToScan+1] = {slot=i, cmd="MULTIACTIONBAR3BUTTON"..(i-24)} end
for i = 37, 48 do slotsToScan[#slotsToScan+1] = {slot=i, cmd="MULTIACTIONBAR4BUTTON"..(i-36)} end

local function scan()
  local byHash = {}
  for _, info in ipairs(slotsToScan) do
    local slot = info.slot
    local name, sid = spellIdFromSlot(slot)
    if name then
      local bind = GetBindingKey(info.cmd)
      local kc, mods = keyNumbers(bind)
      local usable = IsUsableAction(slot) and 1 or 0
      local cdStart, cdDur = GetActionCooldown(slot)
      local ready = (usable == 1 and cdDur == 0) and 1 or 0
      local r = IsActionInRange(slot)
      local inRange = (r == nil) and -1 or ((r == 1) and 1 or 0)
      local hash = H(name)
      local entry = {hash=hash, spellId=sid, slot=slot, keyCode=kc, mods=mods, ready=ready, inRange=inRange}
      local prev = byHash[hash]
      if not prev then
        byHash[hash] = entry
      else
        -- предпочитаем слот с клавишей
        if prev.keyCode == 0 and entry.keyCode ~= 0 then byHash[hash] = entry end
      end
    end
  end
  -- записать в S
  local arr = {}
  for _, e in pairs(byHash) do arr[#arr+1] = e end
  -- S layout: S[1],S[2] marks, S[3] counter, S[4] lastHash, S[5] time, S[6] N, then entries
  S[3] = S[3] or 0
  S[4] = S[4] or 0
  S[5] = GetTime()
  S[6] = #arr
  local idx = 7
  for _, e in ipairs(arr) do
    S[idx] = e.hash; idx = idx + 1
    S[idx] = e.spellId; idx = idx + 1
    S[idx] = e.slot; idx = idx + 1
    S[idx] = e.keyCode; idx = idx + 1
    S[idx] = e.mods; idx = idx + 1
    S[idx] = e.ready; idx = idx + 1
    S[idx] = e.inRange; idx = idx + 1
  end
end

-- накопление elapsed
local acc = 0
local frame = CreateFrame("Frame")
frame:SetScript("OnUpdate", function(self, elapsed)
  local ok, err = pcall(function()
    acc = acc + elapsed
    if acc >= 0.5 then
      acc = 0
      scan()
    end
  end)
  if not ok then print("LUA ERR", err) end
end)

-- события применения
local f = CreateFrame("Frame")
f:RegisterEvent("UNIT_SPELLCAST_SUCCEEDED")
f:SetScript("OnEvent", function(self, event, unit, spellName)
  local ok, err = pcall(function()
    if unit ~= "player" then return end
    if not spellName then return end
    S[4] = H(spellName)
    S[3] = (S[3] or 0) + 1
  end)
  if not ok then print("LUA ERR", err) end
end)

-- первичный скан
pcall(scan)
