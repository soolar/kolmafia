package net.sourceforge.kolmafia;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.kolmafia.AdventureResult.AdventureLongCountResult;
import net.sourceforge.kolmafia.objectpool.Concoction;
import net.sourceforge.kolmafia.objectpool.ConcoctionPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.persistence.AdventureDatabase;
import net.sourceforge.kolmafia.persistence.CoinmastersDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.request.coinmaster.CoinMasterRequest;
import net.sourceforge.kolmafia.request.coinmaster.HermitRequest;
import net.sourceforge.kolmafia.request.coinmaster.shop.CoinMasterShopRequest;
import net.sourceforge.kolmafia.shop.ShopDatabase;
import net.sourceforge.kolmafia.shop.ShopDatabase.SHOP;
import net.sourceforge.kolmafia.shop.ShopRow;
import net.sourceforge.kolmafia.shop.ShopRowDatabase;
import net.sourceforge.kolmafia.utilities.LockableListFactory;

public class CoinmasterData implements Comparable<CoinmasterData> {

  public static final AdventureResult MEAT =
      new AdventureLongCountResult(AdventureResult.MEAT, 1) {
        @Override
        public String toString() {
          return this.getCount() + " Meat";
        }

        @Override
        public String getPluralName(long price) {
          return "Meat";
        }
      };

  // *** For testing
  private boolean disabled = false;

  // Mandatory fields
  private final String master;
  private final String nickname;
  private final Class<? extends CoinMasterRequest> requestClass;

  // Optional fields

  // For shop.php Coinmasters
  private String shopId = null;

  // The token(s) that you exchange for items.
  private String token = null;
  private String tokenTest = null;
  private boolean positiveTest = false;
  private Pattern tokenPattern = null;
  private AdventureResult item = null;
  private String property = null;
  private String zone = null;

  // For (old style) Coinmasters that deal with "rows", a map from item id to row number
  private Map<Integer, Integer> itemRows = null;

  // For (new style) Coinmasters that deal with "rows", a list of ShopRow objects
  private List<ShopRow> shopRows = null;

  // The base URL used to buy things from this Coinmaster
  private String buyURL = null;
  private String buyAction = null;
  private List<AdventureResult> buyItems = null;
  private Map<Integer, Integer> buyPrices = null;

  // The base URL used to sell things to this Coinmaster
  private String sellURL = null;
  private String sellAction = null;
  private List<AdventureResult> sellItems = null;
  private Map<Integer, Integer> sellPrices = null;

  // Fields assumed to be common to buying & selling
  private String itemField = null;
  private Pattern itemPattern = null;
  private String countField = null;
  private Pattern countPattern = null;
  private String storageAction = null;
  private String tradeAllAction = null;
  private boolean needsPasswordHash = false;

  // False if the coinmaster doesn't sell anything that goes into
  // inventory. I.e., whether we need to construct PurchaseRequests.
  private boolean canPurchase = true;

  // Derived fields
  public String pluralToken = null;
  private AdventureResult tokenItem = null;
  private Set<AdventureResult> currencies = null;
  private String rootZone = null;

  // Functional fields to obviate overriding methods
  private Function<Integer, Integer> getBuyPrice = this::getBuyPriceInternal;
  private Function<Integer, AdventureResult> itemBuyPrice = this::itemBuyPriceInternal;
  private Function<Integer, Boolean> canBuyItem = this::canBuyItemInternal;
  private Function<Integer, Boolean> availableItem = this::availableItemInternal;
  private Function<Integer, Boolean> availableSkill = this::availableSkillInternal;
  private BiConsumer<AdventureResult, Boolean> purchasedItem = this::purchasedItemInternal;
  private Consumer<String> visitShop = this::visitShopInternal;
  private BiConsumer<List<ShopRow>, Boolean> visitShopRows = this::visitShopRowsInternal;
  private Supplier<String> canBuy = this::canBuyInternal;
  private Supplier<String> canSell = this::canSellInternal;
  private Supplier<String> accessible = this::accessibleInternal;
  private Supplier<Boolean> equip = this::equipInternal;
  private Supplier<Boolean> unequip = this::unequipInternal;

  // Constructor for CoinmasterData with only mandatory fields.
  // Optional fields can be added fluidly.
  // Derived fields are built lazily

  public CoinmasterData(
      String master, final String nickname, final Class<? extends CoinMasterRequest> requestClass) {
    this.master = master;
    this.nickname = nickname;
    this.requestClass = requestClass;
  }

  // Fluid field construction

  /**
   * Defines the token used by the coinmaster,
   *
   * <p>A shop.php coinmaster needs a shopId
   *
   * @param shopId - whichshop=SHOPID
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withShopId(String shopId) {
    this.shopId = shopId;
    return this;
  }

  /**
   * Defines the token used by the coinmaster,
   *
   * <p>A coinmaster deals in currencies other than Meat. If there is only one such currency, we
   * call it the "token"
   *
   * @param token - Token used
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withToken(String token) {
    this.token = token;
    return this;
  }

  /**
   * Defines the plural name of the coinmaster's token.
   *
   * <p>The plural name is ordinarily lazily derived by adding "s" to the end of the token's name,
   * or, if it is an item, using the item's plural name. If that does not suffice, specifying it
   * here overrides lazy derivation.
   *
   * @param pluralToken - Plural name for the token.
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withPluralToken(String pluralToken) {
    this.pluralToken = pluralToken;
    return this;
  }

  /**
   * Defines text that indicates you have no tokens available to spend.
   *
   * <p>When examining the responseText that shows the coinmaster's inventory, usually the available
   * amount of each currency is displayed.
   *
   * <p>If there is only one currency - the token - and there is a simple string indicating that
   * none are available, provide it here. If it is present, we need not do a pattern search to
   * calculate the number of available tokens.
   *
   * @param tokenTest - String indicating zero tokens available
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withTokenTest(String tokenTest) {
    this.tokenTest = tokenTest;
    return this;
  }

  /**
   * Defines whether tokenTest is positive or negative.
   *
   * <p>Occasionally (we have one example), a coinmaster has a single token, but instead of a simple
   * string indicating no tokens are available, there is a string indicating you have at least one.
   *
   * @param positiveTest - true if tokenTest is positive, false if negative
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withPositiveTest(boolean positiveTest) {
    this.positiveTest = positiveTest;
    return this;
  }

  /**
   * Defines a <code>Pattern</code> that determines the number of available tokens.
   *
   * <p>Every coinmaster with a single currency has text informing you of how much of the currency
   * you have available. We use this pattern to extract that number in <code>group(1)</code>.
   *
   * <p>If the token is a number, this should equal the quantity in inventory. We do not expect
   * inventory to get out of sync, but this allows us to check.
   *
   * <p>If the token is virtual (balance stored in a property), this allows us to set (correct) the
   * property
   *
   * @param tokenPattern - <code>Pattern</code> where <code>group(1)</code> is available tokens
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withTokenPattern(Pattern tokenPattern) {
    this.tokenPattern = tokenPattern;
    return this;
  }

  /**
   * Defines a real item that the coinmaster uses as currency.
   *
   * <p>If the coinmaster uses an actual item from inventory (or, rarely, storage), this is it.
   *
   * <p>This is mutually exclusive with <code>property</code>. Provide one or the other.
   *
   * @param item - An AdventureResult for the item used as currency
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItem(AdventureResult item) {
    this.item = item;
    return this;
  }

  /**
   * Defines the property for the balance of a virtual item used as currency
   *
   * <p>If the coinmaster uses a virtual item for currency, this property holds the available
   * balance.
   *
   * <p>This is mutually exclusive with <code>item</code>. Provide one or the other.
   *
   * @param property - The name of a property with the balance of virtual tokens
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withProperty(String property) {
    this.property = property;
    return this;
  }

  /**
   * Specifies that the "rows" of this <code>shop.php</code> coinmaster are all listed in <code>
   * coinmasters.txt</code> using the new ShopRow format.
   *
   * <p>If the coinmaster uses modern <code>shop.php</code>, every purchasable item has a "row"
   * associated with it. These are unique across all shops, even if they refer to the same item.
   *
   * <p>If the rows are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the rows for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withShopRows(String master) {
    this.shopRows = CoinmastersDatabase.getShopRows(master);
    if (this.shopRows == null) {
      // None are configured in coinmasters.txt, yet.
      this.shopRows = LockableListFactory.getInstance(ShopRow.class);
    }
    return this;
  }

  /**
   * Specifies that the "rows" of this <code>shop.php</code> coinmaster are all listed in <code>
   * coinmasters.txt</code>.
   *
   * <p>If the coinmaster uses modern <code>shop.php</code>, every purchasable item has a "row"
   * associated with it. These are unique across all shops, even if they refer to the same item.
   *
   * <p>If the rows are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the rows for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemRows(String master) {
    return withItemRows(CoinmastersDatabase.getRows(master));
  }

  /**
   * Provides an empty <code>Map</code> from <code>itemId</code>s of the items that you can buy from
   * this coinmaster to the <code>row</code> that you need to purchase it.
   *
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemRows() {
    return withItemRows(CoinmastersDatabase.getNewMap());
  }

  /**
   * Provides the "rows" of this <code>shop.php</code> coinmaster when they are not (all) specified
   * in <code>coinmasters.txt</code>.
   *
   * <p>If the coinmaster uses modern <code>shop.php</code>, every purchasable item has a "row"
   * associated with it. These are unique across all shops, even if they refer to the same item.
   *
   * <p>If the rows are not (all) specified in <code>coinmasters.txt</code> - usually because they
   * are variable or seasonal (see Mr. Store or The Swagger Shop), this is how you provide a
   * runtime-constructed map from itemId -> row #.
   *
   * @param itemRows - A map from itemId -> row # for shop inventory
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemRows(Map<Integer, Integer> itemRows) {
    this.itemRows = itemRows;
    return this;
  }

  /**
   * Provides the base URL for purchasing from this coinmaster. It will be augmented with additional
   * fields for action, item, and quantity
   *
   * @param buyURL - The URL for purchasing from this coinmaster
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyURL(String buyURL) {
    this.buyURL = buyURL;
    return this;
  }

  /**
   * Provides the value of the "action" field specifying that you are buying an item from this
   * coinmaster.
   *
   * @param buyAction - The "action" field specifying a purchase
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyAction(String buyAction) {
    this.buyAction = buyAction;
    return this;
  }

  /**
   * Provides a <code>List</code> of <code>AdventureResult</code>s of the items that you can buy
   * from this coinmaster.
   *
   * <p>If the items are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the items for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyItems(String master) {
    return withBuyItems(CoinmastersDatabase.getBuyItems(master));
  }

  /**
   * Provides an empty <code>List</code> of <code>AdventureResult</code>s of the items that you can
   * buy from this coinmaster.
   *
   * <p>The <code>List</code> is expected to be filled in programmatically at runtime when you visit
   * the coinmaster and see what is for sale.
   *
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyItems() {
    return withBuyItems(CoinmastersDatabase.getNewList());
  }

  /**
   * Provides a <code>List</code> of <code>AdventureResult</code>s of the items that you can buy
   * from this coinmaster.
   *
   * <p>If not all the items are in <code>coinmasters.txt</code> - or a <code>List</code> has been
   * created ahead of time elsewhere - pass it in here.
   *
   * <p>It is important that the <code>List</code> be created by <code>LockableListFactory</code>
   * since it will be used as the model for this coinmaster in the <code>CoinmastersFrame</code>
   *
   * @param buyItems - The <code>List</code> of items you can buy
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyItems(List<AdventureResult> buyItems) {
    this.buyItems = buyItems;
    return this;
  }

  /**
   * Provides a <code>Map</code> from itemId to cost of the items that you can buy from this
   * coinmaster.
   *
   * <p>If the items are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the items for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyPrices(String master) {
    return withBuyPrices(CoinmastersDatabase.getBuyPrices(master));
  }

  /**
   * Provides an empty <code>Map</code> from itemId to cost of the items that you can buy from this
   * coinmaster.
   *
   * <p>The <code>Map</code> is expected to be filled in programmatically at runtime when you visit
   * the coinmaster and see what is for sale.
   *
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyPrices() {
    return withBuyPrices(CoinmastersDatabase.getNewMap());
  }

  /**
   * Provides a <code>Map</code> from itemId to cost of the items that you can buy from this
   * coinmaster.
   *
   * <p>If not all the items are in <code>coinmasters.txt</code> - or a <code>Map</code> has been
   * created ahead of time elsewhere - pass it in here.
   *
   * @param buyPrices - The <code>Map</code> from itemId -> cost of items you can buy
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withBuyPrices(Map<Integer, Integer> buyPrices) {
    this.buyPrices = buyPrices;
    return this;
  }

  /**
   * Provides the base URL for selling to this coinmaster. It will be augmented with additional
   * fields for action, item, and quantity
   *
   * @param sellURL - The URL for selling to this coinmaster
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellURL(String sellURL) {
    this.sellURL = sellURL;
    return this;
  }

  /**
   * Provides the value of the "action" field specifying that you are selling an item to this
   * coinmaster.
   *
   * @param sellAction - The "action" field specifying a sale
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellAction(String sellAction) {
    this.sellAction = sellAction;
    return this;
  }

  /**
   * Provides a <code>List</code> of <code>AdventureResult</code>s of the items that you can sell to
   * this coinmaster.
   *
   * <p>If the items are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the items for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellItems(String master) {
    return withSellItems(CoinmastersDatabase.getSellItems(master));
  }

  /**
   * Provides a <code>List</code> of <code>AdventureResult</code>s of the items that you can sell to
   * this coinmaster.
   *
   * <p>If not all the items are in <code>coinmasters.txt</code> - or a <code>List</code> has been
   * created ahead of time elsewhere - pass it in here.
   *
   * <p>It is important that the <code>List</code> be created by <code>LockableListFactory</code>
   * since it will be used as the model for this coinmaster in the <code>CoinmastersFrame</code>
   *
   * @param sellItems - The <code>List</code> of items you can sell
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellItems(List<AdventureResult> sellItems) {
    this.sellItems = sellItems;
    return this;
  }

  /**
   * Provides a <code>Map</code> from itemId to cost of the items that you can sell to this
   * coinmaster.
   *
   * <p>If the items are all provided in <code>coinmasters.txt</code>, pass in the "master" name to
   * get all the items for that coinmaster.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellPrices(String master) {
    return withSellPrices(CoinmastersDatabase.getSellPrices(master));
  }

  /**
   * Provides a <code>Map</code> from itemId to cost of the items that you can sell to this
   * coinmaster.
   *
   * <p>If not all the items are in <code>coinmasters.txt</code> - or a <code>Map</code> has been
   * created ahead of time elsewhere - pass it in here.
   *
   * @param sellPrices - The <code>Map</code> from itemId -> cost of items you can buy
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withSellPrices(Map<Integer, Integer> sellPrices) {
    this.sellPrices = sellPrices;
    return this;
  }

  /**
   * Provides the value of the field specifying the itemId of an item you are buying from or selling
   * to the coinmaster.
   *
   * @param itemField - The field specifying an itemId
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemField(String itemField) {
    this.itemField = itemField;
    return this;
  }

  /**
   * Provides the <code>Pattern</code> for extracting the itemID from a URL when buying from or
   * selling to the coinmaster.
   *
   * @param itemPattern - The <code>Pattern</code> for extracting itemId in <code>group(1)</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemPattern(Pattern itemPattern) {
    this.itemPattern = itemPattern;
    return this;
  }

  /**
   * Provides the value of the field specifying the number of items you are buying from or selling
   * to the coinmaster.
   *
   * @param countField - The field specifying the count
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCountField(String countField) {
    this.countField = countField;
    return this;
  }

  /**
   * Provides the <code>Pattern</code> for extracting the number of items from a URL when buying
   * from or selling to the coinmaster.
   *
   * @param countPattern - The <code>Pattern</code> for extracting count in <code>group(1)</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCountPattern(Pattern countPattern) {
    this.countPattern = countPattern;
    return this;
  }

  /**
   * Provides the value of the field specifying that the coinmaster should take tokens from storage
   *
   * @param storageAction - The field appended to the base URL to specify using storage
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withStorageAction(String storageAction) {
    this.storageAction = storageAction;
    return this;
  }

  /**
   * Provides the value of the field specifying that the coinmaster should trade as many tokens for
   * items as inventory (or storage) allows.
   *
   * @param tradeAllAction - The field appended to the base URL to specify maximum purchase
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withTradeAllAction(String tradeAllAction) {
    this.tradeAllAction = tradeAllAction;
    return this;
  }

  /**
   * Specifies that transactions with this coinmaster need to provide the "pwd" field for the
   * password hash.
   *
   * <p>When you look at the HTML for visiting a coinmaster, sometimes the buttons include a "pwd"
   * field and sometimes they do not. If the former, call this with <code>true</code>. (<code>false
   * </code> is the default.)
   *
   * @param needsPasswordHash - "true" if password hash needed.
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withNeedsPasswordHash(boolean needsPasswordHash) {
    this.needsPasswordHash = needsPasswordHash;
    return this;
  }

  /**
   * Specifies that at least some transactions with this coinmaster deal with actual items from
   * inventory.
   *
   * <p>We've seen at least one coinmaster where purchases did not result in acquiring an item in
   * inventory; instead the item was sent as a "gift" to another player.
   *
   * <p>Specify <code>false</code> here to indicate that we do not need to construct <code>
   * PurchaseRequest</code>s for the items you can buy.
   *
   * @param canPurchase - "false" if buying does not provide an item in inventory
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCanPurchase(boolean canPurchase) {
    this.canPurchase = canPurchase;
    return this;
  }

  // Functional fields to obviate overriding methods

  /**
   * Specifies a static method that will be invoked by <code>int getBuyPrice(int itemId)</code>
   *
   * @param function - a Function object to be called by getBuyPrice
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withGetBuyPrice(Function<Integer, Integer> function) {
    this.getBuyPrice = function;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>
   * AdventureResult itemBuyPrice(int itemId)</code>
   *
   * @param function - a Function object to be called by itemBuyPrice
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withItemBuyPrice(Function<Integer, AdventureResult> function) {
    this.itemBuyPrice = function;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>boolean canBuyItem(int itemId)</code>
   *
   * @param function - a Function object to be called by canBuyItem
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCanBuyItem(Function<Integer, Boolean> function) {
    this.canBuyItem = function;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>boolean availableItem(int itemId)
   * </code>
   *
   * @param function - a Function object to be called by availableItem
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withAvailableItem(Function<Integer, Boolean> function) {
    this.availableItem = function;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>boolean availableSkill(int skillId)
   * </code>
   *
   * @param function - a Function object to be called by availableSkill
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withAvailableSkill(Function<Integer, Boolean> function) {
    this.availableSkill = function;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>void
   * purchasedItem(AdventureResult item, boolean storage)</code>
   *
   * <p>Use this if you want to, for example, set properties for once-per-day (or
   * once-per-ascension) purchases
   *
   * @param consumer - a BiConsumer object to be called by purchasedItem
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withPurchasedItem(BiConsumer<AdventureResult, Boolean> consumer) {
    this.purchasedItem = consumer;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>void
   * visitShop(String responseText)</code>
   *
   * <p>Use this if you want to, for example, check for unlocked items.
   *
   * @param consumer - a Consumer object to be called by visitShop
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withVisitShop(Consumer<String> consumer) {
    this.visitShop = consumer;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>void
   * visitShopRows(List<ShopRows> shopRows)</code>
   *
   * <p>Use this if you want to, for example, check for unlocked items.
   *
   * @param consumer - a Consumer object to be called by visitShopRows
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withVisitShopRows(BiConsumer<List<ShopRow>, Boolean> consumer) {
    this.visitShopRows = consumer;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>Boolean
   * canBuy()</code>
   *
   * @param supplier - a Supplier object to be called by canBuy
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCanBuy(Supplier<String> supplier) {
    this.canBuy = supplier;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>Boolean
   * canSell()</code>
   *
   * @param supplier - a Supplier object to be called by canSell
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withCanSell(Supplier<String> supplier) {
    this.canSell = supplier;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>String
   * accessible()</code>
   *
   * @param supplier - a Supplier object to be called by accessible
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withAccessible(Supplier<String> supplier) {
    this.accessible = supplier;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>String
   * equip()</code>
   *
   * @param supplier - a Supplier object to be called by equip
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withEquip(Supplier<Boolean> supplier) {
    this.equip = supplier;
    return this;
  }

  /**
   * Specifies a static method that will be invoked by <code>String
   * unequip()</code>
   *
   * @param supplier - a Supplier object to be called by equip
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withUnequip(Supplier<Boolean> supplier) {
    this.unequip = supplier;
    return this;
  }

  /**
   * Populates the ten fields for a standard <code>shop.php</code> coinmaster that uses row #s.
   *
   * <ul>
   *   <li>shopId
   *   <li>itemRows
   *   <li>buyURL
   *   <li>buyAction
   *   <li>buyItems from <code>coinmasters.txt</code>
   *   <li>buyPrices from <code>coinmasters.txt</code>
   *   <li>itemField
   *   <li>itemPattern
   *   <li>countField
   *   <li>countPattern
   * </ul>
   *
   * <p>Note that if your coinmaster has multiple currencies and/or variable inventory (which means
   * you will be programmatically building rows/items/prices at runtime), you can still use this to
   * fill in the fields; you will simply need to follow up with withItemRows, withBuyItems,
   * withBuyPrices as needed.
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @param shopId - The value of the shopId parameter in <code>shop.php</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withShopRowFields(String master, String shopId) {
    return this.withShopId(shopId)
        .withItemRows(master)
        .withBuyURL("shop.php?whichshop=" + shopId)
        .withBuyAction("buyitem")
        .withBuyItems(master)
        .withBuyPrices(master)
        .withItemField("whichrow")
        .withItemPattern(GenericRequest.WHICHROW_PATTERN)
        .withCountField("quantity")
        .withCountPattern(GenericRequest.QUANTITY_PATTERN)
        .withNeedsPasswordHash(true);
  }

  /**
   * Populates the eight fields for a new <code>shop.php</code> coinmaster that uses row #s.
   *
   * <ul>
   *   <li>shopId
   *   <li>shopRows from <code>coinmasters.txt</code>
   *   <li>buyURL
   *   <li>buyAction
   *   <li>itemField
   *   <li>itemPattern
   *   <li>countField
   *   <li>countPattern
   * </ul>
   *
   * @param master - The name of the shop in <code>coinmasters.txt</code>
   * @param shopId - The value of the shopId parameter in <code>shop.php</code>
   * @return this - Allows fluid chaining of fields
   */
  public CoinmasterData withNewShopRowFields(String master, String shopId) {
    return this.withShopId(shopId)
        .withShopRows(master)
        .withBuyURL("shop.php?whichshop=" + shopId)
        .withBuyAction("buyitem")
        .withItemField("whichrow")
        .withItemPattern(GenericRequest.WHICHROW_PATTERN)
        .withCountField("quantity")
        .withCountPattern(GenericRequest.QUANTITY_PATTERN)
        .withNeedsPasswordHash(true);
  }

  public CoinmasterData inZone(String zone) {
    this.zone = zone;
    return this;
  }

  // Getters for mandatory fields

  public final String getMaster() {
    return this.master;
  }

  public final String getNickname() {
    return this.nickname;
  }

  public final Class<? extends CoinMasterRequest> getRequestClass() {
    return this.requestClass;
  }

  public final String getToken() {
    return this.token;
  }

  public final void setToken(final String token) {
    this.token = token;
    this.pluralToken = null;
  }

  // Getters for optional fields

  public final String getShopId() {
    return this.shopId;
  }

  public final String getTokenTest() {
    return this.tokenTest;
  }

  public final boolean getPositiveTest() {
    return this.positiveTest;
  }

  public final Pattern getTokenPattern() {
    return this.tokenPattern;
  }

  public final AdventureResult getItem() {
    return this.item;
  }

  public final void setItem(final AdventureResult item) {
    this.item = item;
  }

  public final String getProperty() {
    return this.property;
  }

  public List<ShopRow> getShopRows() {
    return this.shopRows;
  }

  public final ShopRow getShopRow(int thingId) {
    if (this.shopRows == null) {
      return null;
    }
    for (ShopRow shopRow : this.shopRows) {
      var adventureResult = shopRow.getItem();
      if (adventureResult.getItemId() == thingId || adventureResult.getSkillId() == thingId) {
        return shopRow;
      }
    }
    return null;
  }

  public void setShopRows(List<ShopRow> shopRows) {
    if (this.shopRows != null) {
      this.shopRows.clear();
      this.shopRows.addAll(shopRows);
    }
  }

  public final void addShopRow(ShopRow shopRow) {
    if (this.shopRows != null) {
      this.shopRows.add(shopRow);
    }
  }

  public Map<Integer, Integer> getRows() {
    return this.itemRows;
  }

  public final Integer getRow(int itemId) {
    if (this.itemRows == null) {
      return 0;
    }
    Integer row = this.itemRows.get(itemId);
    return row;
  }

  public final Integer getItemIdOrRow(int itemId) {
    if (this.itemRows == null) {
      return itemId;
    }
    Integer row = this.itemRows.get(itemId);
    return row;
  }

  public final boolean hasRow(int row) {
    if (this.itemRows == null) {
      return false;
    }
    return this.itemRows.containsValue(row);
  }

  // The base URL used to buy things from this Coinmaster
  public final String getBuyURL() {
    return this.buyURL;
  }

  public final String getBuyAction() {
    return this.buyAction;
  }

  public final List<AdventureResult> getBuyItems() {
    return this.buyItems;
  }

  public final Map<Integer, Integer> getBuyPrices() {
    return this.buyPrices;
  }

  // The base URL used to sell things to this Coinmaster
  public final String getSellURL() {
    return this.sellURL;
  }

  public final String getSellAction() {
    return this.sellAction;
  }

  public final List<AdventureResult> getSellItems() {
    return this.sellItems;
  }

  public final Map<Integer, Integer> getSellPrices() {
    return this.sellPrices;
  }

  // Fields assumed to be common to buying & selling

  public final String getItemField() {
    return this.itemField;
  }

  public final Pattern getItemPattern() {
    return this.itemPattern;
  }

  public final Matcher getItemMatcher(final String string) {
    return this.itemPattern == null ? null : this.itemPattern.matcher(string);
  }

  public final String getCountField() {
    return this.countField;
  }

  public final Pattern getCountPattern() {
    return this.countPattern;
  }

  public final Matcher getCountMatcher(final String string) {
    return this.countPattern == null ? null : this.countPattern.matcher(string);
  }

  public final String getStorageAction() {
    return this.storageAction;
  }

  public final String getTradeAllAction() {
    return this.tradeAllAction;
  }

  public final boolean needsPasswordHash() {
    return this.needsPasswordHash;
  }

  public final boolean getCanPurchase() {
    return this.canPurchase;
  }

  // Getters for derived fields

  public final String getPluralToken() {
    if (this.pluralToken == null) {
      this.pluralToken =
          this.item != null ? ItemDatabase.getPluralName(this.token) : this.token + "s";
    }
    return this.pluralToken;
  }

  private AdventureResult getTokenItem() {
    if (this.tokenItem == null) {
      this.tokenItem =
          new AdventureResult(this.token, -1, 1, false) {
            @Override
            public String getPluralName(final long count) {
              return count == 1
                  ? CoinmasterData.this.getToken()
                  : CoinmasterData.this.getPluralToken();
            }
          };
    }
    return this.tokenItem;
  }

  public final int availableTokens() {
    AdventureResult item = this.item;
    if (item != null) {
      return item.getItemId() == ItemPool.WORTHLESS_ITEM
          ? HermitRequest.getWorthlessItemCount()
          : item.getCount(KoLConstants.inventory);
    }
    String property = this.property;
    if (property != null) {
      return Preferences.getInteger(property);
    }
    return 0;
  }

  public final int availableTokens(final AdventureResult currency) {
    if (currency.isMeat()) {
      return Concoction.getAvailableMeat();
    }

    int itemId = currency.getItemId();

    if (itemId != -1) {
      return itemId == ItemPool.WORTHLESS_ITEM
          ? HermitRequest.getWorthlessItemCount()
          : currency.getCount(KoLConstants.inventory);
    }

    if (this.property != null) {
      return Preferences.getInteger(this.property);
    }

    return 0;
  }

  public final int availableStorageTokens() {
    return this.storageAction != null ? this.item.getCount(KoLConstants.storage) : 0;
  }

  public final int availableStorageTokens(final AdventureResult currency) {
    return this.storageAction != null && currency.getItemId() != -1
        ? currency.getCount(KoLConstants.storage)
        : 0;
  }

  public final int affordableTokens(final AdventureResult currency) {
    if (currency.isMeat()) {
      return Concoction.getAvailableMeat();
    }

    return currency.getItemId() == ItemPool.WORTHLESS_ITEM
        ? HermitRequest.getAcquirableWorthlessItemCount()
        : this.availableTokens(currency);
  }

  public Boolean availableItem(final Integer itemId) {
    return this.availableItem.apply(itemId);
  }

  private Boolean availableItemInternal(final Integer itemId) {
    if (this.shopRows != null) {
      for (ShopRow shopRow : this.shopRows) {
        AdventureResult item = shopRow.getItem();
        if (item.isItem() && item.getItemId() == itemId) {
          return true;
        }
      }
      return false;
    }

    if (this.buyItems == null) {
      return false;
    }
    AdventureResult item = ItemPool.get(itemId, 1);
    return (this.buyItems.contains(item));
  }

  public Boolean availableSkill(final Integer skillId) {
    return this.availableSkill.apply(skillId);
  }

  private Boolean availableSkillInternal(final Integer skillId) {
    if (this.shopRows == null) {
      return false;
    }

    for (ShopRow shopRow : this.shopRows) {
      AdventureResult item = shopRow.getItem();
      if (item.isSkill() && item.getSkillId() == skillId) {
        // The Coinmaster may have additional restrictions, but by
        // default, if they sell it and you don't have it, cool.
        return !KoLCharacter.hasSkill(skillId);
      }
    }
    return false;
  }

  public Boolean canBuyItem(final Integer itemId) {
    return this.canBuyItem.apply(itemId);
  }

  public Boolean canBuyItemInternal(final Integer itemId) {
    if (this.shopRows != null) {
      for (ShopRow shopRow : this.shopRows) {
        AdventureResult item = shopRow.getItem();
        if (item.isItem() && item.getItemId() == itemId) {
          return true;
        }
      }
      return false;
    }

    if (this.buyItems == null) {
      return false;
    }

    AdventureResult item = ItemPool.get(itemId, 1);
    return item.getCount(this.buyItems) > 0;
  }

  public Integer getBuyPrice(final Integer itemId) {
    return this.getBuyPrice.apply(itemId);
  }

  private Integer getBuyPriceInternal(final Integer itemId) {
    if (this.buyPrices == null) {
      return 0;
    }

    Integer price = this.buyPrices.get(itemId);
    return price != null ? price : 0;
  }

  public AdventureResult itemBuyPrice(final Integer itemId) {
    return this.itemBuyPrice.apply(itemId);
  }

  private AdventureResult itemBuyPriceInternal(final Integer itemId) {
    int price = this.getBuyPrice(itemId);
    return this.item != null
        ? this.item.getInstance(price)
        : this.token != null ? this.getTokenItem().getInstance(price) : null;
  }

  public AdventureResult skillBuyPrice(final Integer skillId) {
    // We only support "modern" shop coinmasters for skills
    if (this.shopRows == null) {
      return null;
    }
    for (ShopRow shopRow : this.shopRows) {
      AdventureResult item = shopRow.getItem();
      if (item.isSkill() && item.getSkillId() == skillId) {
        AdventureResult[] costs = shopRow.getCosts();
        return (costs.length == 1) ? costs[0] : null;
      }
    }
    return null;
  }

  public Set<AdventureResult> currencies() {
    if (this.currencies == null) {
      this.currencies = new TreeSet<>();
      if (this.master.equals("Hermit")) {
        // Unlike other coinmasters, buyitems is not initialized until
        // the shop is first visited.
        this.currencies.add(HermitRequest.WORTHLESS_ITEM);
      } else if (this.shopRows != null) {
        for (ShopRow shopRow : this.shopRows) {
          for (AdventureResult cost : shopRow.getCosts()) {
            this.currencies.add(cost);
          }
        }
      } else if (this.buyItems != null) {
        for (AdventureResult item : this.buyItems) {
          this.currencies.add(this.itemBuyPrice(item.getItemId()));
        }
      }
    }
    return this.currencies;
  }

  public final boolean canSellItem(final int itemId) {
    if (this.sellPrices != null) {
      return this.sellPrices.containsKey(itemId);
    }
    return false;
  }

  public final int getSellPrice(final int itemId) {
    if (this.sellPrices != null) {
      Integer price = this.sellPrices.get(itemId);
      return price != null ? price : 0;
    }
    return 0;
  }

  public AdventureResult itemSellPrice(final int itemId) {
    int price = this.getSellPrice(itemId);
    return this.item == null
        ? AdventureResult.tallyItem(this.token, price, false)
        : this.item.getInstance(price);
  }

  // Override this method if certain rows should not get a Concoction or
  // CoinMasterPurchaseRequest.
  public boolean manualOnlyRow(final ShopRow shopRow) {
    return false;
  }

  public void registerPurchaseRequests() {
    // If this Coin Master doesn't sell anything that goes into
    // your inventory, nothing to register
    if (!this.canPurchase) {
      return;
    }

    // Clear existing purchase requests
    CoinmastersDatabase.clearPurchaseRequests(this);

    // If no buyItems registered, nothing to register
    if (this.shopRows == null && this.buyItems == null) {
      return;
    }

    if (this.shopRows != null) {
      Set<AdventureResult> currencies = this.currencies();
      for (ShopRow row : this.shopRows) {
        AdventureResult item = row.getItem();

        // If the item is a currency and you get more than one when you
        // buy it with this row, it is conceptually a "sell" request.
        //
        // A coinmaster can both create an item and use such an item to
        // make another item - for example, Grandma Sea Monkey with a
        // Mer-kin gladiator mask or a Mer-kin scholar mask - and if the
        // yield of the "currency" is 1, we want treat those as "buys".

        if (item.getCount() > 1 && currencies.contains(item)) {
          // Do not make a PurchaseRequest for a "sell"
          continue;
        }

        CoinmastersDatabase.registerPurchaseRequest(this, row);
      }
      return;
    }

    // For each item you can buy from this Coin Master, create a purchase request
    for (AdventureResult item : this.buyItems) {
      AdventureResult price = this.itemBuyPrice(item.getItemId());
      CoinmastersDatabase.registerPurchaseRequest(this, item, price);
    }
  }

  public void registerCurrencies() {
    for (AdventureResult currency : this.currencies()) {
      if (currency.isItem()) {
        CoinmastersDatabase.registerCurrency(currency);
      }
    }
  }

  public void registerShop() {
    if (this.buyURL.startsWith("shop.php")) {
      ShopDatabase.registerShop(this.shopId, this.master, SHOP.COIN);
      ShopDatabase.setCoinmasterData(this.shopId, this);
      ShopDatabase.setLogVisits(shopId);
    }
  }

  public void registerShopRows() {
    if (this.shopId == null) {
      return;
    }
    if (this.shopRows != null) {
      for (ShopRow shopRow : this.shopRows) {
        ShopRowDatabase.registerShopRow(shopRow, this.shopId);
      }
    }
    if (this.buyItems != null) {
      for (AdventureResult item : this.buyItems) {
        int itemId = item.getItemId();
        int row = this.getRow(itemId);
        if (row != 0) {
          AdventureResult price = this.itemBuyPrice(itemId);
          ShopRow shopRow = new ShopRow(row, item.getInstance(1), price);
          ShopRowDatabase.registerShopRow(shopRow, this.shopId);
        }
      }
    }
    if (this.sellItems != null) {
      for (AdventureResult item : this.sellItems) {
        int itemId = item.getItemId();
        int row = this.getRow(itemId);
        if (row != 0) {
          AdventureResult price = this.itemSellPrice(itemId);
          ShopRow shopRow = new ShopRow(row, price, item);
          ShopRowDatabase.registerShopRow(shopRow, this.shopId);
        }
      }
    }
  }

  public void registerPropertyToken() {
    if (this.property == null) {
      return;
    }
    ConcoctionPool.set(new Concoction(this.token, this.property));
  }

  public String getZone() {
    return this.zone;
  }

  public String getRootZone() {
    if (this.zone == null) {
      // Reset, for testing
      this.rootZone = null;
    } else if (this.rootZone == null) {
      this.rootZone = AdventureDatabase.getRootZone(this.zone);
    }
    return this.rootZone;
  }

  @Override
  public String toString() {
    return this.master;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof CoinmasterData data && this.master == data.master;
  }

  @Override
  public int hashCode() {
    return this.master != null ? this.master.hashCode() : 0;
  }

  @Override
  public int compareTo(final CoinmasterData cd) {
    return this.master.compareToIgnoreCase(cd.master);
  }

  public CoinMasterRequest getRequest() {
    if (this.shopId != null) {
      return new CoinMasterShopRequest(this);
    }

    Class<? extends CoinMasterRequest> requestClass = this.getRequestClass();
    Class<?>[] parameters = new Class<?>[0];

    try {
      Constructor<? extends CoinMasterRequest> constructor =
          requestClass.getConstructor(parameters);
      Object[] initargs = new Object[0];
      return constructor.newInstance(initargs);
    } catch (Exception e) {
      return null;
    }
  }

  public CoinMasterRequest getRequest(final boolean buying, final AdventureResult[] items) {
    if (this.shopId != null) {
      return new CoinMasterShopRequest(this, buying, items);
    }

    Class<? extends CoinMasterRequest> requestClass = this.getRequestClass();
    Class<?>[] parameters = new Class<?>[2];
    parameters[0] = boolean.class;
    parameters[1] = AdventureResult[].class;

    try {
      Constructor<? extends CoinMasterRequest> constructor =
          requestClass.getConstructor(parameters);
      Object[] initargs = new Object[2];
      initargs[0] = buying;
      initargs[1] = items;
      return constructor.newInstance(initargs);
    } catch (Exception e) {
      return null;
    }
  }

  public CoinMasterRequest getRequest(final ShopRow row, final int quantity) {
    if (this.shopId != null) {
      return new CoinMasterShopRequest(this, row, quantity);
    }
    // If you are not using shop.php, you don't use ShopRows
    return null;
  }

  public boolean isAccessible() {
    return this.accessible() == null;
  }

  public String accessible() {
    return this.accessible.get();
  }

  public String accessibleInternal() {
    // Returns an error reason or null
    if ("Removed".equals(this.getRootZone())) {
      return "Zone is no longer accessible";
    }
    return null;
  }

  public String canSell() {
    // Returns an error reason or null
    return this.canSell.get();
  }

  public String canSellInternal() {
    return null;
  }

  public String canBuy() {
    // Returns an error reason or null
    return this.canBuy.get();
  }

  public String canBuyInternal() {
    return null;
  }

  public void purchasedItem(final AdventureResult item, final Boolean storage) {
    this.purchasedItem.accept(item, storage);
  }

  private void purchasedItemInternal(AdventureResult item, boolean storage) {}

  public void visitShop(final String responseText) {
    this.visitShop.accept(responseText);
  }

  private void visitShopInternal(String responseText) {}

  public void visitShopRows(final List<ShopRow> shopRows, Boolean force) {
    this.visitShopRows.accept(shopRows, force);
  }

  private void visitShopRowsInternal(List<ShopRow> shopRows, Boolean force) {}

  public Boolean equip() {
    return this.equip.get();
  }

  public Boolean equipInternal() {
    return true;
  }

  public Boolean unequip() {
    return this.unequip.get();
  }

  public Boolean unequipInternal() {
    return true;
  }

  // *** For testing
  public void setDisabled(boolean isDisabled) {
    this.disabled = isDisabled;
  }

  public boolean isDisabled() {
    return this.disabled;
  }
}
