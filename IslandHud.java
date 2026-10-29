package dev.dynamicisland;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Dynamic Island поверх HUD.
 *
 *  COMPACT : голова скина + ник, ниже HP.
 *  CHAT    : анимация -> голова отправителя + ник + текст, 10 секунд.
 *  FULL    : "Jade" — на что смотришь (блок / моб / игрок) + твоя строка статуса.
 *
 * Клавиша J (меняется в настройках управления) переключает COMPACT <-> FULL.
 */
public final class IslandHud {
	private static final long POPUP_MS = 10_000L;
	private static final long MIN_SHOW_MS = 2_500L; // если в очереди есть ещё сообщения

	private enum Mode { COMPACT, FULL, CHAT }

	private record Msg(String nick, String text) {}

	private static final class Target {
		String name = "";
		String ns = "";
		ItemStack stack = ItemStack.EMPTY;
		LivingEntity living;
		AbstractClientPlayer player;
	}

	// <Nick> text
	private static final Pattern VANILLA = Pattern.compile("^<([^>]{1,48})>\\s*(.+)$", Pattern.DOTALL);
	// [Rank] Nick: text   |   Nick » text   |   Nick > text
	private static final Pattern GENERIC = Pattern.compile(
			"^(?:\\[[^\\]]{1,24}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*[:»›>]+\\s*(.+)$", Pattern.DOTALL);

	private static final Deque<Msg> queue = new ArrayDeque<>();
	private static Msg current;
	private static long curStart;
	private static boolean full;

	// анимация (пружина)
	private static float w = 48, h = 14, vw, vh;
	private static long lastNanos;
	private static Object stateKey;
	private static long stateSince;

	private IslandHud() {}

	// ------------------------------------------------------------ события

	public static synchronized void toggleFull() {
		full = !full;
	}

	public static synchronized void onMessage(Component component) {
		String raw = component.getString().replaceAll("§.", "").strip();
		if (raw.isEmpty()) return;

		String nick = null;
		String text = raw;

		Matcher m = VANILLA.matcher(raw);
		if (m.matches()) {
			nick = m.group(1).replaceAll("^(\\[[^\\]]*\\]\\s*)+", "").strip();
			int sp = nick.lastIndexOf(' ');
			if (sp >= 0) nick = nick.substring(sp + 1);
			text = m.group(2).strip();
		} else {
			m = GENERIC.matcher(raw);
			if (m.matches()) {
				nick = m.group(1);
				text = m.group(2).strip();
			}
		}
		if (nick == null || nick.isEmpty()) nick = "Сервер";
		if (nick.length() > 16) nick = nick.substring(0, 16);

		if (queue.size() >= 6) queue.pollFirst();
		queue.addLast(new Msg(nick, text));
	}

	private static void advance(long now) {
		if (current != null) {
			boolean expired = now - curStart >= POPUP_MS;
			boolean skip = !queue.isEmpty() && now - curStart >= MIN_SHOW_MS;
			if (expired || skip) current = null;
		}
		if (current == null && !queue.isEmpty()) {
			current = queue.pollFirst();
			curStart = now;
		}
	}

	// ------------------------------------------------------------ рендер

	public static synchronized void render(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		Font font = mc.font;

		long nowMs = System.nanoTime() / 1_000_000L;
		advance(nowMs);

		Mode mode = current != null ? Mode.CHAT : (full ? Mode.FULL : Mode.COMPACT);
		Object key;
		if (mode == Mode.CHAT) key = current; else key = mode;
		if (key != stateKey) {
			stateKey = key;
			stateSince = nowMs;
		}

		// ---- целевой размер
		int tw, th;
		List<String> lines = null;
		Target target = null;
		String ownHp = hpText(mc.player);

		if (mode == Mode.CHAT) {
			lines = wrap(font, current.text(), 190, 2);
			int tx = font.width(current.nick());
			for (String l : lines) tx = Math.max(tx, font.width(l));
			tw = clamp(tx + 36 + 14, 136, 262);
			th = Math.max(34, 6 + 10 + 10 * lines.size() + 9);
		} else if (mode == Mode.FULL) {
			target = findTarget(mc);
			String l1 = target != null ? target.name : "Нет цели";
			boolean hasNs = target != null && !target.ns.isEmpty();
			boolean hasHp = target != null && target.living != null;
			int textW = font.width(l1);
			if (hasNs) textW = Math.max(textW, font.width(target.ns));
			if (hasHp) textW = Math.max(textW, 12 + font.width(hpText(target.living)));
			BlockPos bp = mc.player.blockPosition();
			String foot = footText(mc, bp);
			int footW = 22 + font.width(ownHp) + 10 + font.width(foot);
			tw = clamp(Math.max(32 + textW + 14, footW + 10), 170, 320);
			th = headerHeight(hasNs, hasHp) + 19;
		} else {
			String name = mc.player.getName().getString();
			int tx = Math.max(font.width(name), 12 + font.width(ownHp));
			tw = Math.max(104, 33 + tx + 14);
			th = 32;
		}

		// ---- пружина
		long nowNs = System.nanoTime();
		float dtSec = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nowNs - lastNanos) / 1_000_000_000f);
		lastNanos = nowNs;
		final float k = 300f, c = 24f;
		float rem = dtSec;
		while (rem > 0f) {
			float s = Math.min(rem, 0.004f);
			vw += ((tw - w) * k - vw * c) * s;
			w += vw * s;
			vh += ((th - h) * k - vh * c) * s;
			h += vh * s;
			rem -= s;
		}
		w = Math.max(w, 36f);
		h = Math.max(h, 14f);

		int iw = Math.round(w), ih = Math.round(h);
		int sw = mc.getWindow().getGuiScaledWidth();
		int x = (sw - iw) / 2;
		int y = 5;

		pill(g, x - 1, y - 1, iw + 2, ih + 2, 0xFF2B2B30);
		pill(g, x, y, iw, ih, 0xFF000000);

		// появление контента после начала морфа
		float p = clamp01((nowMs - stateSince - 60) / 200f);
		int a = Math.round(255f * p * p * (3f - 2f * p));
		if (a <= 4) return;

		g.enableScissor(x + 2, y + 1, x + iw - 2, y + ih - 1);
		if (mode == Mode.CHAT) {
			drawChat(g, mc, font, x, y, iw, ih, a, lines, nowMs);
		} else if (mode == Mode.FULL) {
			drawFull(g, mc, font, x, y, iw, ih, a, target, ownHp);
		} else {
			drawCompact(g, mc, font, x, y, ih, a, ownHp);
		}
		g.disableScissor();
	}

	// ------------------------------------------------------------ режимы

	private static void drawCompact(GuiGraphicsExtractor g, Minecraft mc, Font font,
									int x, int y, int h, int a, String hp) {
		var p = mc.player;
		if (a > 120) {
			PlayerFaceExtractor.extractRenderState(g, p.getSkin(), x + 7, y + (h - 20) / 2, 20);
		}
		text(g, font, p.getName().getString(), x + 33, y + 6, 0xFFFFFF, a);
		heart(g, x + 33, y + 18, a);
		text(g, font, hp, x + 45, y + 18, hpColor(p), a);
	}

	private static void drawChat(GuiGraphicsExtractor g, Minecraft mc, Font font,
								 int x, int y, int w, int h, int a, List<String> lines, long now) {
		Msg m = current;
		if (m == null || lines == null) return;
		drawHeadOf(g, mc, m.nick(), x + 7, y + (h - 22) / 2, 22, a);
		text(g, font, m.nick(), x + 36, y + 6, 0x5AC8FA, a);
		for (int i = 0; i < lines.size(); i++) {
			text(g, font, lines.get(i), x + 36, y + 17 + 10 * i, 0xEDEDED, a);
		}
		// полоска оставшегося времени
		float frac = clamp01(1f - (now - curStart) / (float) POPUP_MS);
		int bx = x + 36, bw = Math.max(0, w - 36 - 12);
		g.fill(bx, y + h - 5, bx + bw, y + h - 3, col(0xFFFFFF, a / 5));
		g.fill(bx, y + h - 5, bx + Math.round(bw * frac), y + h - 3, col(0x5AC8FA, a));
	}

	private static void drawFull(GuiGraphicsExtractor g, Minecraft mc, Font font,
								 int x, int y, int w, int h, int a, Target t, String ownHp) {
		boolean hasNs = t != null && !t.ns.isEmpty();
		boolean hasHp = t != null && t.living != null;
		int hh = headerHeight(hasNs, hasHp);

		if (t == null) {
			text(g, font, "Нет цели", x + 12, y + 7, 0x8E8E93, a);
		} else {
			if (t.player != null) {
				if (a > 120) PlayerFaceExtractor.extractRenderState(g, t.player.getSkin(), x + 8, y + 7, 18);
			} else if (!t.stack.isEmpty() && a > 120) {
				g.item(t.stack, x + 8, y + 7);
			}
			text(g, font, t.name, x + 32, y + 7, 0xFFFFFF, a);
			int ly = y + 17;
			if (hasNs) {
				text(g, font, t.ns, x + 32, ly, 0x7B8CFF, a);
				ly += 10;
			}
			if (hasHp) {
				heart(g, x + 32, ly, a);
				text(g, font, hpText(t.living), x + 44, ly, hpColor(t.living), a);
			}
		}

		// разделитель + своя строка
		g.fill(x + 10, y + hh, x + w - 10, y + hh + 1, col(0xFFFFFF, a / 6));
		int fy = y + hh + 6;
		heart(g, x + 10, fy, a);
		text(g, font, ownHp, x + 22, fy, hpColor(mc.player), a);
		text(g, font, footText(mc, mc.player.blockPosition()),
				x + 22 + font.width(ownHp) + 10, fy, 0xB0B0B8, a);
	}

	private static int headerHeight(boolean hasNs, boolean hasHp) {
		return Math.max(30, 7 + 10 + (hasNs ? 10 : 0) + (hasHp ? 10 : 0) + 5);
	}

	private static String footText(Minecraft mc, BlockPos bp) {
		return "Еда " + mc.player.getFoodData().getFoodLevel()
				+ "  ·  " + bp.getX() + " " + bp.getY() + " " + bp.getZ();
	}

	// ------------------------------------------------------------ "Jade"

	private static Target findTarget(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit == null) return null;

		if (hit instanceof EntityHitResult eh) {
			Entity e = eh.getEntity();
			if (e == null || e == mc.player) return null;
			Target t = new Target();
			t.name = e.getDisplayName().getString();
			t.ns = pretty(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace());
			if (e instanceof LivingEntity le) t.living = le;
			if (e instanceof AbstractClientPlayer acp) t.player = acp;
			return t;
		}
		if (hit instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(bh.getBlockPos());
			if (state.isAir()) return null;
			Target t = new Target();
			t.name = state.getBlock().getName().getString();
			t.ns = pretty(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
			t.stack = new ItemStack(state.getBlock());
			return t;
		}
		return null;
	}

	private static String pretty(String ns) {
		if (ns == null || ns.isEmpty()) return "";
		return Character.toUpperCase(ns.charAt(0)) + ns.substring(1);
	}

	// ------------------------------------------------------------ HP

	private static String hpText(LivingEntity e) {
		String s = fmt(e.getHealth()) + " / " + fmt(e.getMaxHealth());
		float abs = e.getAbsorptionAmount();
		if (abs > 0.01f) s += " +" + fmt(abs);
		return s;
	}

	private static int hpColor(LivingEntity e) {
		float ratio = e.getMaxHealth() <= 0 ? 1f : e.getHealth() / e.getMaxHealth();
		if (ratio > 0.5f) return 0x55FF55;
		if (ratio > 0.25f) return 0xFFFF55;
		return 0xFF5555;
	}

	private static String fmt(float v) {
		if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
		return String.format(Locale.ROOT, "%.1f", v);
	}

	// ------------------------------------------------------------ голова

	private static void drawHeadOf(GuiGraphicsExtractor g, Minecraft mc, String nick,
								   int x, int y, int size, int a) {
		if (a <= 120) return;
		var conn = mc.getConnection();
		var info = conn == null ? null : conn.getPlayerInfo(nick);
		if (info != null) {
			PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
			return;
		}
		// заглушка: серый квадрат с буквой
		g.fill(x, y, x + size, y + size, col(0x3A3A3C, a));
		String letter = nick.isEmpty() ? "?" : nick.substring(0, 1).toUpperCase(Locale.ROOT);
		Font font = mc.font;
		text(g, font, letter, x + (size - font.width(letter)) / 2, y + (size - 8) / 2, 0xFFFFFF, a);
	}

	// ------------------------------------------------------------ примитивы

	private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int rgb, int a) {
		g.text(font, s, x, y, col(rgb, a), false);
	}

	private static int col(int rgb, int a) {
		return (clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static final String[] HEART = {
			"011000110",
			"111101111",
			"111111111",
			"111111111",
			"011111110",
			"001111100",
			"000111000",
			"000010000"
	};

	private static void heart(GuiGraphicsExtractor g, int x, int y, int a) {
		int c = col(0xFF3B4B, a);
		for (int r = 0; r < HEART.length; r++) {
			String row = HEART[r];
			int start = -1;
			for (int i = 0; i <= row.length(); i++) {
				boolean on = i < row.length() && row.charAt(i) == '1';
				if (on && start < 0) start = i;
				if (!on && start >= 0) {
					g.fill(x + start, y + r, x + i, y + r + 1, c);
					start = -1;
				}
			}
		}
	}

	/** Скруглённая "таблетка" из горизонтальных полос. */
	private static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
		int r = Math.min(h / 2, 15);
		for (int row = 0; row < h; row++) {
			int dy;
			if (row < r) dy = r - row;
			else if (row >= h - r) dy = row - (h - r) + 1;
			else dy = 0;
			int inset = 0;
			if (dy > 0) {
				double d = dy - 0.5;
				inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - d * d)));
			}
			int x1 = x + inset, x2 = x + w - inset;
			if (x2 > x1) g.fill(x1, y + row, x2, y + row + 1, argb);
		}
	}

	// ------------------------------------------------------------ текст

	private static List<String> wrap(Font font, String text, int maxW, int maxLines) {
		List<String> out = new ArrayList<>();
		String rest = text.replace('\n', ' ').strip();
		while (!rest.isEmpty() && out.size() < maxLines) {
			if (font.width(rest) <= maxW) {
				out.add(rest);
				break;
			}
			boolean last = out.size() == maxLines - 1;
			int cut = fit(font, rest, maxW - (last ? font.width("...") : 0));
			if (cut <= 0) cut = 1;
			if (last) {
				out.add(rest.substring(0, cut).stripTrailing() + "...");
				break;
			}
			int sp = rest.lastIndexOf(' ', cut);
			if (sp > cut / 3) {
				out.add(rest.substring(0, sp));
				rest = rest.substring(sp + 1).stripLeading();
			} else {
				out.add(rest.substring(0, cut));
				rest = rest.substring(cut);
			}
		}
		if (out.isEmpty()) out.add("");
		return out;
	}

	/** Наибольшее n, при котором ширина s[0..n) <= maxW. */
	private static int fit(Font font, String s, int maxW) {
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (font.width(s.substring(0, mid)) <= maxW) lo = mid; else hi = mid - 1;
		}
		return lo;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
