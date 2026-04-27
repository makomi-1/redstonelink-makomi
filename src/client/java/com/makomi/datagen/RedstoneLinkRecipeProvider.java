package com.makomi.datagen;

import com.makomi.RedstoneLink;
import com.makomi.registry.ModItems;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * RedstoneLink 配方数据生成器。
 * <p>
 * 统一维护运行时使用的 20 个配方，避免资源目录路径或配方内容再次漂移。
 */
public class RedstoneLinkRecipeProvider extends FabricRecipeProvider {
	private static final TagKey<Item> TRIGGER_P_MATERIALS = modItemTag("trigger_p_materials");
	private static final TagKey<Item> TRIGGER_S_MATERIALS = modItemTag("trigger_s_materials");
	private static final TagKey<Item> TRIGGER_T_MATERIALS = modItemTag("trigger_t_materials");

	/**
	 * 创建配方数据提供器。
	 *
	 * @param output 数据输出上下文
	 * @param registriesFuture 注册表查询上下文
	 */
	public RedstoneLinkRecipeProvider(
		FabricDataOutput output,
		CompletableFuture<HolderLookup.Provider> registriesFuture
	) {
		super(output, registriesFuture);
	}

	/**
	 * 按 1.21.11 datagen 新入口创建实际配方生成器。
	 */
	@Override
	protected RecipeProvider createRecipeProvider(HolderLookup.Provider registryLookup, RecipeOutput exporter) {
		return new RecipeGenerator(registryLookup, exporter);
	}

	/**
	 * 返回数据生成器名称，满足 1.21.11 DataProvider 新契约。
	 */
	@Override
	public String getName() {
		return "RedstoneLink Recipes";
	}

	/**
	 * 1.21.11 配方生成主体。
	 */
	private final class RecipeGenerator extends RecipeProvider {
		private RecipeGenerator(HolderLookup.Provider registryLookup, RecipeOutput exporter) {
			super(registryLookup, exporter);
		}

		@Override
		public void buildRecipes() {
			buildRedstoneLinkComponentRecipe(output);
			buildLinkBaseRecipe(output, "link_toggle_button", ModItems.LINK_TOGGLE_BUTTON, Items.STONE_BUTTON);
			buildLinkBaseRecipe(output, "link_push_button", ModItems.LINK_PUSH_BUTTON, ItemTags.WOODEN_BUTTONS);
			buildLinkBaseRecipe(output, "link_sync_lever", ModItems.LINK_SYNC_LEVER, Items.LEVER);
			buildLinkBaseRecipe(output, "link_pulse_emitter", ModItems.LINK_PULSE_EMITTER, TRIGGER_P_MATERIALS);
			buildLinkBaseRecipe(output, "link_sync_emitter", ModItems.LINK_SYNC_EMITTER, TRIGGER_S_MATERIALS);
			buildLinkBaseRecipe(output, "link_toggle_emitter", ModItems.LINK_TOGGLE_EMITTER, TRIGGER_T_MATERIALS);
			buildSendFilterRecipe(output);
			buildReceiveFilterRecipe(output);
			buildChunkActivatorRecipe(output);
			buildLinkBaseRecipe(output, "link_redstone_core", ModItems.LINK_REDSTONE_CORE, Items.REDSTONE_BLOCK);
			buildLinkBaseRecipe(output, "link_redstone_dust_core", ModItems.LINK_REDSTONE_DUST_CORE, Items.REDSTONE);
			buildLinkerRecipe(output, "redstonelink_toggle_linker", ModItems.REDSTONELINK_TOGGLE_LINKER, ModItems.LINK_TOGGLE_BUTTON);
			buildLinkerRecipe(output, "redstonelink_pulse_linker", ModItems.REDSTONELINK_PULSE_LINKER, ModItems.LINK_PUSH_BUTTON);
			buildLinkerRecipe(output, "redstonelink_sync_linker", ModItems.REDSTONELINK_SYNC_LINKER, ModItems.LINK_SYNC_LEVER);
			buildQuickLinkToolRecipe(output);
			buildStatusPanelRecipe(output);
			buildTransparentCoreSwapRecipe(output, "link_redstone_core_transparent", ModItems.LINK_REDSTONE_CORE_TRANSPARENT, ModItems.LINK_REDSTONE_CORE);
			buildTransparentCoreSwapRecipe(output, "link_redstone_core_from_transparent", ModItems.LINK_REDSTONE_CORE, ModItems.LINK_REDSTONE_CORE_TRANSPARENT);
			buildTransparentCoreSwapRecipe(
				output,
				"link_redstone_dust_core_transparent",
				ModItems.LINK_REDSTONE_DUST_CORE_TRANSPARENT,
				ModItems.LINK_REDSTONE_DUST_CORE
			);
			buildTransparentCoreSwapRecipe(
				output,
				"link_redstone_dust_core_from_transparent",
				ModItems.LINK_REDSTONE_DUST_CORE,
				ModItems.LINK_REDSTONE_DUST_CORE_TRANSPARENT
			);
		}

		/**
		 * 生成核心基础元件配方。
		 */
		private void buildRedstoneLinkComponentRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.REDSTONE_LINK_COMPONENT, 3)
				.define('C', Items.COPPER_INGOT)
				.define('R', Items.REDSTONE)
				.define('I', Items.IRON_INGOT)
				.pattern(" I ")
				.pattern("CRC")
				.pattern(" I ")
				.unlockedBy(getHasName(Items.REDSTONE), has(Items.REDSTONE))
				.save(recipeOutput, recipeId("redstone_link_component"));
		}

		/**
		 * 生成“基础材质 + 红石连接原件”模板配方。
		 */
		private void buildLinkBaseRecipe(
			RecipeOutput recipeOutput,
			String recipeId,
			ItemLike result,
			ItemLike baseIngredient
		) {
			shaped(RecipeCategory.REDSTONE, result)
				.define('B', baseIngredient)
				.define('L', ModItems.REDSTONE_LINK_COMPONENT)
				.pattern("   ")
				.pattern("BL ")
				.pattern("   ")
				.unlockedBy(getHasName(baseIngredient), has(baseIngredient))
				.save(recipeOutput, recipeId(recipeId));
		}

		/**
		 * 生成“标签材质 + 红石连接原件”模板配方。
		 */
		private void buildLinkBaseRecipe(
			RecipeOutput recipeOutput,
			String recipeId,
			ItemLike result,
			TagKey<Item> baseIngredientTag
		) {
			shaped(RecipeCategory.REDSTONE, result)
				.define('B', baseIngredientTag)
				.define('L', ModItems.REDSTONE_LINK_COMPONENT)
				.pattern("   ")
				.pattern("BL ")
				.pattern("   ")
				.unlockedBy(tagCriterionName(baseIngredientTag), has(baseIngredientTag))
				.save(recipeOutput, recipeId(recipeId));
		}

		/**
		 * 生成区块激活器专用配方：中心连接原件，外围一圈土方块。
		 */
		private void buildChunkActivatorRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.LINK_CHUNK_ACTIVATOR)
				.define('D', Items.DIRT)
				.define('C', ModItems.REDSTONE_LINK_COMPONENT)
				.pattern("DDD")
				.pattern("DCD")
				.pattern("DDD")
				.unlockedBy(getHasName(ModItems.REDSTONE_LINK_COMPONENT), has(ModItems.REDSTONE_LINK_COMPONENT))
				.save(recipeOutput, recipeId("link_chunk_activator"));
		}

		/**
		 * 生成发送过滤器配方。
		 */
		private void buildSendFilterRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.LINK_SEND_FILTER)
				.define('G', Items.GLASS_PANE)
				.define('I', Items.IRON_INGOT)
				.define('L', ModItems.REDSTONE_LINK_COMPONENT)
				.define('S', ModItems.LINK_SYNC_EMITTER)
				.pattern(" I ")
				.pattern("GLG")
				.pattern(" S ")
				.unlockedBy(getHasName(ModItems.LINK_SYNC_EMITTER), has(ModItems.LINK_SYNC_EMITTER))
				.save(recipeOutput, recipeId("link_send_filter"));
		}

		/**
		 * 生成接收过滤器配方。
		 */
		private void buildReceiveFilterRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.LINK_RECEIVE_FILTER)
				.define('C', ModItems.LINK_REDSTONE_CORE)
				.define('G', Items.GLASS_PANE)
				.define('I', Items.IRON_INGOT)
				.define('L', ModItems.REDSTONE_LINK_COMPONENT)
				.pattern(" I ")
				.pattern("GLG")
				.pattern(" C ")
				.unlockedBy(getHasName(ModItems.LINK_REDSTONE_CORE), has(ModItems.LINK_REDSTONE_CORE))
				.save(recipeOutput, recipeId("link_receive_filter"));
		}

		/**
		 * 生成 linker 派生配方。
		 */
		private void buildLinkerRecipe(
			RecipeOutput recipeOutput,
			String recipeId,
			ItemLike result,
			ItemLike baseIngredient
		) {
			shaped(RecipeCategory.REDSTONE, result)
				.define('B', baseIngredient)
				.define('L', Items.LEVER)
				.pattern("   ")
				.pattern("BL ")
				.pattern("   ")
				.unlockedBy(getHasName(baseIngredient), has(baseIngredient))
				.save(recipeOutput, recipeId(recipeId));
		}

		/**
		 * 生成 quick-link tool 配方。
		 */
		private void buildQuickLinkToolRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.QUICK_LINK_TOOL)
				.define('C', ModItems.REDSTONE_LINK_COMPONENT)
				.define('S', Items.STICK)
				.pattern("  C")
				.pattern(" S ")
				.pattern("S  ")
				.unlockedBy(getHasName(ModItems.REDSTONE_LINK_COMPONENT), has(ModItems.REDSTONE_LINK_COMPONENT))
				.save(recipeOutput, recipeId("quick_link_tool"));
		}

		/**
		 * 生成状态面板配方。
		 */
		private void buildStatusPanelRecipe(RecipeOutput recipeOutput) {
			shaped(RecipeCategory.REDSTONE, ModItems.REDSTONELINK_STATUS_PANEL)
				.define('G', Items.GLASS)
				.define('S', ModItems.LINK_SYNC_EMITTER)
				.define('C', ModItems.REDSTONE_LINK_COMPONENT)
				.define('R', ModItems.LINK_REDSTONE_CORE)
				.pattern(" G ")
				.pattern("SCR")
				.pattern("   ")
				.unlockedBy(getHasName(ModItems.LINK_SYNC_EMITTER), has(ModItems.LINK_SYNC_EMITTER))
				.save(recipeOutput, recipeId("redstonelink_status_panel"));
		}

		/**
		 * 生成透明/非透明 core 的互转配方。
		 */
		private void buildTransparentCoreSwapRecipe(
			RecipeOutput recipeOutput,
			String recipeId,
			ItemLike result,
			ItemLike ingredient
		) {
			shapeless(RecipeCategory.REDSTONE, result)
				.requires(ingredient)
				.unlockedBy(getHasName(ingredient), has(ingredient))
				.save(recipeOutput, recipeId(recipeId));
		}
	}

	/**
	 * 将配方路径转换为 1.21.11 `save` 需要的字符串资源标识。
	 */
	private static String recipeId(String path) {
		return id(path).toString();
	}

	/**
	 * 生成模组资源标识符。
	 */
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	/**
	 * 生成模组物品标签键。
	 */
	private static TagKey<Item> modItemTag(String path) {
		return TagKey.create(Registries.ITEM, id(path));
	}

	/**
	 * 生成标签解锁条件名称。
	 */
	private static String tagCriterionName(TagKey<Item> tagKey) {
		return "has_" + tagKey.location().getPath();
	}
}
