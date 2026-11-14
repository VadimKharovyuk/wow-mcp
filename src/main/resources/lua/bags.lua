-- bags.lua -- table I: bags, vendor, sell queue, repair
I = I or {777005.5,777006.5,0,0,0,0,0,0,0,0,0}
KEEP = KEEP or {}

local function now() return GetTime() end

local function money()
  local m = GetMoney and GetMoney() or 0
  return m
end

local function getClasses()
  local t = {GetAuctionItemClasses()}
  return t
end

local classes = getClasses()
local function classIs(className, idx)
  return classes[idx] and className == classes[idx]
end

local function isJunk(bag, slot)
  local link = GetContainerItemLink(bag, slot)
  if not link then return false, nil end
  local name, _, quality, _, _, className, _, _, equipLoc, _, sellPrice = GetItemInfo(link)
  local itemId = tonumber(link:match("item:(%d+)"))
  if itemId and KEEP[itemId] then return false, link end
  if not sellPrice or sellPrice <= 0 then return false, link end
  -- forbidden classes: Quest(12), Consumable(4), Trade Goods(6), Recipe(9), Bag(3)
  if className and (classIs(className,12) or classIs(className,4) or classIs(className,6) or classIs(className,9) or classIs(className,3)) then
    return false, link
  end
  if quality == 0 then
    return true, link
  end
  if quality == 1 then
    -- white junk only: Miscellaneous(11), cannot equip
    if equipLoc and equipLoc ~= "" then return false, link end
    if className and not classIs(className,11) then return false, link end
    return true, link
  end
  return false, link
end

local function countBagSlots()
  local free, total = 0, 0
  for b = 0, 4 do
    local n = GetContainerNumSlots(b) or 0
    local f, t = GetContainerNumFreeSlots(b)
    -- t (bag type) == 0 means normal bag
    if n > 0 and (t == 0 or t == nil) then
      free = free + (f or 0)
      total = total + n
    end
  end
  return free, total
end

local function countToSell()
  local c = 0
  for b = 0, 4 do
    local n = GetContainerNumSlots(b) or 0
    for s = 1, n do
      local ok, link = isJunk(b, s)
      if ok then c = c + 1 end
    end
  end
  return c
end

local sellQueue = nil
local sellAcc = 0
local function buildSellQueue()
  local q = {}
  for b = 0, 4 do
    local n = GetContainerNumSlots(b) or 0
    for s = 1, n do
      local ok, link = isJunk(b, s)
      if ok and link then
        q[#q+1] = {b=b, s=s, link=link}
      end
    end
  end
  sellQueue = q
  sellAcc = 0
end

local function merchantShown()
  return MerchantFrame and MerchantFrame:IsShown()
end

local function doRepairIfNeeded()
  local ok, err = pcall(function()
    if CanMerchantRepair and CanMerchantRepair() then
      local cost = GetRepairAllCost() or 0
      I[9] = cost
      if cost > 0 and (GetMoney() or 0) >= cost then
        RepairAllItems()
      end
    else
      I[9] = 0
    end
  end)
  if not ok then print("LUA ERR", err) end
end

local function closeMerchantSafe()
  local ok, err = pcall(function()
    if CloseMerchant then CloseMerchant() end
  end)
  if not ok then print("LUA ERR", err) end
end

local function processSell(elapsed)
  if not sellQueue then return end
  sellAcc = sellAcc + elapsed
  if sellAcc < 0.3 then return end
  sellAcc = 0
  if not merchantShown() or I[6] ~= 1 then
    sellQueue = nil
    return
  end
  while #sellQueue > 0 do
    local it = sellQueue[1]
    local cur = GetContainerItemLink(it.b, it.s)
    local okJunk = isJunk(it.b, it.s)
    if cur ~= it.link or not okJunk then
      table.remove(sellQueue, 1)
    else
      UseContainerItem(it.b, it.s)
      I[8] = (I[8] or 0) + 1
      table.remove(sellQueue, 1)
      return
    end
  end
  -- queue done
  sellQueue = nil
  doRepairIfNeeded()
  -- close only the window opened by the bot (JS sets the flag before clicking)
  if BOTSELL == 1 then
    BOTSELL = nil
    closeMerchantSafe()
  end
end

local function updateBags()
  local ok, err = pcall(function()
    local free, total = countBagSlots()
    I[3] = free
    I[4] = total
    I[7] = countToSell()
    I[9] = GetRepairAllCost and (GetRepairAllCost() or 0) or 0
    I[10] = money()
    local moIsTarget = 0
    if UnitExists("mouseover") and UnitExists("target") and UnitIsUnit("mouseover","target") then
      moIsTarget = 1
    end
    I[11] = moIsTarget
    I[5] = now()
  end)
  if not ok then print("LUA ERR", err) end
end

local accB = 0
local bf = CreateFrame("Frame")
bf:RegisterEvent("MERCHANT_SHOW")
bf:RegisterEvent("MERCHANT_CLOSED")
bf:RegisterEvent("GOSSIP_SHOW")
bf:SetScript("OnEvent", function(self, event)
  local ok, err = pcall(function()
    if event == "MERCHANT_SHOW" then
      I[6] = 1
      buildSellQueue()
    elseif event == "MERCHANT_CLOSED" then
      I[6] = 0
      sellQueue = nil
      BOTSELL = nil
    elseif event == "GOSSIP_SHOW" then
      local opts = {GetGossipOptions()}
      -- flat pairs: text1,type1,text2,type2,...
      local i = 1
      local pair = 1
      while opts[i] and opts[i+1] do
        local gtype = opts[i+1]
        if gtype == "vendor" then
          SelectGossipOption(pair)
          break
        end
        i = i + 2
        pair = pair + 1
      end
    end
  end)
  if not ok then print("LUA ERR", err) end
end)
bf:SetScript("OnUpdate", function(self, elapsed)
  local ok, err = pcall(function()
    accB = accB + elapsed
    if accB >= 0.25 then
      accB = 0
      updateBags()
    end
    processSell(elapsed)
  end)
  if not ok then print("LUA ERR", err) end
end)

pcall(updateBags)
