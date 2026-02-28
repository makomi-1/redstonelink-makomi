import java.lang.reflect.*;

public class DumpMethods {
  public static void dump(String cls) throws Exception {
    Class<?> c = Class.forName(cls);
    System.out.println("==== " + cls + " ====");
    for (Method m : c.getDeclaredMethods()) {
      int mod = m.getModifiers();
      if (Modifier.isPublic(mod) || Modifier.isProtected(mod)) {
        System.out.println(m.toGenericString());
      }
    }
  }
  public static void main(String[] args) throws Exception {
    dump("net.minecraft.world.item.ItemStack");
    dump("net.minecraft.world.item.component.CustomData");
    dump("net.minecraft.core.component.DataComponents");
    dump("net.minecraft.world.level.block.entity.BlockEntity");
    dump("net.minecraft.world.level.saveddata.SavedData");
    dump("net.minecraft.world.level.block.state.BlockBehaviour$Properties");
    dump("net.minecraft.world.level.block.Block");
    dump("net.minecraft.world.level.block.ButtonBlock");
    dump("net.minecraft.world.level.block.state.BlockState");
    dump("net.minecraft.world.level.block.EntityBlock");
    dump("net.minecraft.world.MenuProvider");
  }
}