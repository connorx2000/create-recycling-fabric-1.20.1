package com.highhybrid.createrecycle;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.crusher.CrushingRecipe;

import io.github.fabricators_of_create.porting_lib.util.EnvExecutor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener; // use Fabric's reload listener
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.core.registries.Registries;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.world.level.storage.LevelResource;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class CreateRecycle implements ModInitializer {
	public static final String ID = "createrecycle";
	public static final String NAME = "Create Recycling";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	@Override
	public void onInitialize() {
		LOGGER.info("Loading {} alongside Create {}", NAME, Create.VERSION);
		ServerLifecycleEvents.SERVER_STARTED.register(this::installDatapack);
	}

	private void installDatapack(MinecraftServer server) {
		// Ensure our datapack folder and pack.mcmeta exist
		File worldDir = server.getWorldPath(LevelResource.DATAPACK_DIR).toFile();
		File packFolder = new File(worldDir, ID);
		if (!packFolder.exists()) {
			packFolder.mkdirs();
			// Write pack.mcmeta so Minecraft recognizes it as a datapack
			JsonObject packMeta = new JsonObject();
			JsonObject packObj = new JsonObject();
			packObj.addProperty("pack_format", 15);
			packObj.addProperty("description", NAME + " dynamic recipes");
			packMeta.add("pack", packObj);
			try (FileWriter writer = new FileWriter(new File(packFolder, "pack.mcmeta"))) {
				writer.write(packMeta.toString());
			} catch (Exception e) {
				LOGGER.error("Failed to create pack.mcmeta for dynamic recipes", e);
			}
		}
		RecipeInjector injector = new RecipeInjector(server);
		ResourceManagerHelper.get(PackType.SERVER_DATA)
				.registerReloadListener(injector);
		// Perform initial injection immediately
		injector.apply(server.getResourceManager());
		LOGGER.info("Installed dynamic recycling datapack listener and performed initial injection");
	}

	private record RecipeInjector(MinecraftServer server) implements SimpleSynchronousResourceReloadListener {

		// Store ID's to give full recipe back
		private static final Set<String> FULL_YIELD_ITEMS = Set.of(
				"minecraft:slime_block",
				"minecraft:honeycomb_block",
				"minecraft:dried_kelp_block",
				"minecraft:coal_block",
				"minecraft:iron_block",
				"minecraft:gold_block",
				"minecraft:lapis_block",
				"minecraft:redstone_block",
				"minecraft:diamond_block",
				"minecraft:emerald_block",
				"minecraft:netherite_block",
				"create:andesite_alloy_block",
				"create:brass_block",
				"create:zinc_block",
				"create:experience_block",
				"minecraft:hay_block"
				// Add more item IDs here
		);

		// Store ID's to skip recipe creation
		private static final Set<String> SKIP_RECIPE_ITEMS = Set.of(
				"minecraft:honey_bottle",
				"minecraft:honey_block"
				// Add more item IDs here
		);

		public void apply(ResourceManager resourceManager) {
			RecipeManager rm = server.getRecipeManager();
			var itemRegistry = server.registryAccess().registryOrThrow(Registries.ITEM);
			int count = 0;

			Map<ResourceLocation, Item> existingCrushingRecipes = getExistingCrushingRecipes(rm);

			LOGGER.info("Existing crushing recipes:");
			existingCrushingRecipes.forEach((rl, item) ->
				LOGGER.info("Recipe: {}, Input: {}", rl, item.toString())
			);


			// Item Tags for later use
			TagKey<Item> planksTag = TagKey.create(Registries.ITEM, new ResourceLocation("minecraft", "planks"));
			TagKey<Item> terracottaTag = TagKey.create(Registries.ITEM, new ResourceLocation("minecraft", "terracotta"));
			TagKey<Item> glassTag = TagKey.create(Registries.ITEM, new ResourceLocation("minecraft", "glass"));

			for (CraftingRecipe cr : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
				var recipeId = cr.getId();
				if (recipeId.getNamespace().equals(ID)) continue;

				Item resultItem = cr.getResultItem(server.registryAccess()).getItem();
				ResourceLocation itemRL = itemRegistry.getKey(resultItem);

				if (shouldSkipRecipe(existingCrushingRecipes, itemRL, resultItem, itemRegistry, planksTag, glassTag)) {
					continue;
				}

				JsonObject recipeJson = createRecipeJson(cr, resultItem, itemRL, itemRegistry, terracottaTag, glassTag);
				if (recipeJson != null) {
					writeRecipeFile(itemRL, recipeJson);
					count++;
				}
			}

			LOGGER.info("Injected {} recycling recipes", count);
		}

		private Map<ResourceLocation, Item> getExistingCrushingRecipes(RecipeManager rm) {
			Map<ResourceLocation, Item> existingCrushingRecipes = new HashMap<>();
			for (Recipe<?> recipe : rm.getRecipes()) {
				if (recipe.getId().getPath().contains("crushing") && recipe instanceof CrushingRecipe crushingRecipe) {
					if (!crushingRecipe.getIngredients().isEmpty()) {
						Ingredient firstIngredient = crushingRecipe.getIngredients().get(0);
						ItemStack[] matchingStacks = firstIngredient.getItems();
						if (matchingStacks.length > 0) {
							existingCrushingRecipes.put(recipe.getId(), matchingStacks[0].getItem());
						}
					}
				}
			}
			return existingCrushingRecipes;
		}

		private boolean shouldSkipRecipe(Map<ResourceLocation, Item> existingCrushingRecipes, ResourceLocation itemRL, Item resultItem, Registry<Item> itemRegistry, TagKey<Item> planksTag, TagKey<Item> glassTag) {
			if (existingCrushingRecipes.containsValue(resultItem)) {
				LOGGER.debug("Skipping recipe for {} because a crushing recipe with this item as input already exists", itemRL);
				return true;
			}
			if (SKIP_RECIPE_ITEMS.contains(itemRL.toString())) {
				LOGGER.debug("Skipping recipe for {} because it's in the SKIP_RECIPE_ITEMS set", itemRL);
				return true;
			}
			if (itemRegistry.getHolderOrThrow(itemRegistry.getResourceKey(resultItem).get()).is(planksTag)) {
				LOGGER.debug("Skipping recipe for {} because it has the #planks tag", itemRL);
				return true;
			}
			if (itemRL.getPath().endsWith("_glass_pane")) {
				LOGGER.debug("Skipping recipe for {} because it's a glass pane", itemRL);
				return true;
			}
			if (itemRL.getPath().endsWith("_wool")) {
				LOGGER.debug("Skipping recipe for {} because it's wool", itemRL);
				return true;
			}
			if (itemRL.getPath().startsWith("waxed_")) {
				LOGGER.debug("Skipping recipe for {} because it's a waxed block", itemRL);
				return true;
			}
			return false;
		}

		private JsonObject createRecipeJson(CraftingRecipe cr, Item resultItem, ResourceLocation itemRL, Registry<Item> itemRegistry, TagKey<Item> terracottaTag, TagKey<Item> glassTag) {
			JsonObject json = new JsonObject();
			json.addProperty("type", "create:crushing");
			JsonArray ingredientsArr = new JsonArray();
			ingredientsArr.add(Ingredient.of(new ItemStack(resultItem)).toJson());
			json.add("ingredients", ingredientsArr);
			json.addProperty("processingTime", 350);

			JsonArray results = new JsonArray();

			if (itemRegistry.getHolderOrThrow(itemRegistry.getResourceKey(resultItem).get()).is(terracottaTag)) {
				addTerracottaResult(results);
			} else if (itemRL.getPath().endsWith("_glass")) {
				addGlassResult(results, itemRL);
			} else if (resultItem.toString().endsWith("_carpet")) {
				addCarpetResult(results);
			} else {
				addDefaultResults(cr, itemRL, itemRegistry, results);
			}

			json.add("results", results);

			// Log the entire recipe for debugging
			LOGGER.debug("Created recipe for {}: {}", itemRL, json);

			return json;
		}

		private void addTerracottaResult(JsonArray results) {
			JsonObject sandEntry = new JsonObject();
			sandEntry.addProperty("item", "minecraft:sand");
			sandEntry.addProperty("count", 1);
			results.add(sandEntry);
		}

		private void addGlassResult(JsonArray results, ResourceLocation itemRL) {
			// Always add sand as the main result
			JsonObject sandEntry = new JsonObject();
			sandEntry.addProperty("item", "minecraft:sand");
			sandEntry.addProperty("count", 1);
			results.add(sandEntry);
		}

		private void addCarpetResult(JsonArray results) {
			JsonObject stringEntry = new JsonObject();
			stringEntry.addProperty("item", "minecraft:string");
			stringEntry.addProperty("count", 1);
			results.add(stringEntry);
		}

		private void addDefaultResults(CraftingRecipe cr, ResourceLocation itemRL, Registry<Item> itemRegistry, JsonArray results) {
			Map<Item, Integer> tally = new HashMap<>();
			for (var ing : cr.getIngredients()) {
				var matches = ing.getItems();
				if (matches.length == 0) continue;
				tally.merge(matches[0].getItem(), matches[0].getCount(), Integer::sum);
			}

			boolean isFullYield = FULL_YIELD_ITEMS.contains(itemRL.toString());

			tally.forEach((item, qty) -> {
				String itemKey = itemRegistry.getKey(item).toString();
				if (isFullYield) {
					addFullYieldResult(results, itemKey, qty);
				} else {
					addPartialYieldResult(results, itemKey, qty);
				}
			});
		}

		private void addFullYieldResult(JsonArray results, String itemKey, int qty) {
			JsonObject entry = new JsonObject();
			entry.addProperty("item", itemKey);
			entry.addProperty("count", qty);
			results.add(entry);
		}

		private void addPartialYieldResult(JsonArray results, String itemKey, int qty) {
			int guaranteed = qty / 2;
			boolean hasRemainder = (qty % 2) == 1;
			if (guaranteed > 0) {
				JsonObject entry = new JsonObject();
				entry.addProperty("item", itemKey);
				entry.addProperty("count", guaranteed);
				results.add(entry);
			}
			if (hasRemainder) {
				JsonObject chanceEntry = new JsonObject();
				chanceEntry.addProperty("item", itemKey);
				chanceEntry.addProperty("chance", 0.25);
				results.add(chanceEntry);
			}
		}

		private void writeRecipeFile(ResourceLocation itemRL, JsonObject json) {
			File worldDir = server.getWorldPath(LevelResource.DATAPACK_DIR).toFile();
			File packDir = new File(worldDir, ID + "/data/" + ID + "/recipes");
			File recipeFile = new File(packDir, itemRL.getPath() + "_recycle.json");
			packDir.mkdirs();
			try (FileWriter fw = new FileWriter(recipeFile)) {
				fw.write(json.toString());
				LOGGER.debug("Successfully wrote recipe file for {} at {}", itemRL, recipeFile.getAbsolutePath());
			} catch (Exception e) {
				LOGGER.error("Failed to write recipe {} to {}", itemRL, recipeFile.getAbsolutePath(), e);
			}
		}

		@Override
		public ResourceLocation getFabricId() {
			return new ResourceLocation(ID, "dynamic_recipes");
		}

		@Override
		public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
			// Delegate to apply() for backwards compatibility
			apply(resourceManager);
		}
	}
}
