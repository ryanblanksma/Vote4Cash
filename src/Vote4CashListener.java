import com.vexsoftware.votifier.Votifier;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VoteListener;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

public class Vote4CashListener implements VoteListener, Listener {
	private static final Logger log = Logger.getLogger("Vote4CashListener");

	private static Votifier v = null;
	private static Economy econ = null;

	private static boolean broadcast;
	private static double reward;
	private static String msg;
	private static String broadcastMsg;
	private static String currencyS;
	private static String currencyP;

	private static File dataFolder;
	private static File propertiesFile;
	private static File dataFile;

	private static ArrayList<String> pending;

	public Vote4CashListener() {

		v = Votifier.getInstance();

		// Set up all files
		dataFolder = v.getDataFolder();
		propertiesFile = new File(dataFolder, "Vote4Cash.properties");
		dataFile = new File(dataFolder, "V4CPending.txt");

		// Create a new data file with an empty data array in it if there isn't one already
		if (!dataFile.exists()) {
			ArrayList<String> empty = new ArrayList<String>();
			save(empty);
		}

		if (v != null) {
			Properties pro = new Properties();

			// Create properties file
			if (!propertiesFile.exists()) {
				pro.setProperty("reward", "50");
				pro.setProperty("player-voted-msg", "[Vote4Cash] Thank you for voting %PLAYER%! To show our appreciation here is %REWARD% %CURRENCY%!");
				pro.setProperty("broadcast-msg", "[Vote4Cash] %PLAYER% has voted and received %REWARD% %CURRENCY%. Thank you %PLAYER%!");
				pro.setProperty("currency-name-singular", "dollar");
				pro.setProperty("currency-name-plural", "dollars");
				pro.setProperty("broadcast", "true");
				try {
					pro.store(new FileOutputStream(propertiesFile), "Vote4Cash Properties");
				} catch (Exception e) {
					log.severe("[Vote4Cash] Error creating new properties file!");
				}
			}

			// Load properties file
			try {
				pro.load(new FileInputStream(propertiesFile));
				reward = Double.parseDouble(pro.getProperty("reward"));
				msg = pro.getProperty("player-voted-msg");
				broadcastMsg = pro.getProperty("broadcast-msg");
				currencyS = pro.getProperty("currency-name-singular");
				currencyP = pro.getProperty("currency-name-plural");
				broadcast = Boolean.parseBoolean(pro.getProperty("broadcast"));
			} catch (Exception e) {
				log.severe("[Vote4Cash] Error reading existing properties file!");
			}

			// Hook to vault & get economy
			if (v.getServer().getPluginManager().getPlugin("Vault") != null) {
				try {
				RegisteredServiceProvider<Economy> economyProvider = v.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
				econ = economyProvider.getProvider();
				}
				catch(Exception e) {
					log.severe("[Vote4Cash] Error hooking to Vault! Vote4Cash Listener will not work!");
					log.severe("[Vote4Cash] Error is: "+e.getMessage()+" from "+e.getCause()+".");
				}
			} else {
				log.severe("[Vote4Cash] Could not find Vault! Vote4Cash Listener will not work!");
			}

			// Load pending players from data file
			pending = new ArrayList<String>();
			try {
				BufferedReader br = new BufferedReader(new FileReader(dataFile));
				br.readLine(); // Skip first line of text always
				String text;
				while ((text = br.readLine()) != null) {
					pending.add(text);
				}
				br.close();
			} catch (Exception e) {
				log.severe("[Vote4Cash] Error reading data file! Delayed payment will probably not work.");
			}

			// Register a player listener to do things on player log in
			v.getServer().getPluginManager().registerEvents(this, v);
		}
	}

	public void voteMade(Vote vote) {
		if (econ != null) {
			String player = vote.getUsername();

			// Check if they are on server for instant payment, otherwise put on waiting list if not already on it
			Player[] players = v.getServer().getOnlinePlayers();
			for (int i = 0; i < players.length; i++) {
				String playerName = players[i].getName();
				if (player.equalsIgnoreCase(playerName)) {
					pay(players[i]);
					return;
				}
			}

			// If they got this far they are not on server so check if they are already on pending list
			for (int i = 0; i < pending.size(); i++) {
				if (pending.get(i).equals(player)) {
					log.info("[Vote4Cash] " + player + " is not on the server but is already on the waiting list to receive money, will not submit again.");
					return;
				}
			}

			// Now that they are not on the pending list or on the server, make an entry for them in the pending list!
			log.info("[Vote4Cash] " + player + " was not found on the server, so he will be given money on next login.");
			pending.add(player);
			save(pending);
		}
	}

	public String formatOutput(String mess, String p) {
		String[] split = mess.split("%");
		String returnS = "";
		for (int i = 0; i < split.length; i++) {
			if (split[i].equals("PLAYER")) {
				split[i] = p;
			}
			if (split[i].equals("REWARD")) {
				split[i] = Double.toString(reward);
			}
			if (split[i].equals("CURRENCY")) {
				if (reward > 1) {
					split[i] = currencyP;
				} else {
					split[i] = currencyS;
				}
			}
			returnS = returnS + split[i];
		}
		return returnS;
	}

	@EventHandler(priority = EventPriority.NORMAL)
	public void onPlayerJoin(PlayerJoinEvent e) {
		if (pending.isEmpty()) {
			return;
		}

		for (int i = 0; i < pending.size(); i++) {
			String player = e.getPlayer().getName();
			if (player.equals(pending.get(i))) {
				log.info("[Vote4Cash] Found " + player + " in pending list. Paying now!");
				pay(e.getPlayer());
				pending.remove(i);
				save(pending);
			}
		}
	}

	public void pay(Player player) {
		EconomyResponse r = econ.depositPlayer(player.getName(), reward);
		if (r.transactionSuccess()) {
			player.sendMessage(formatOutput(msg, player.getName()));
			log.info("[Vote4Cash] " + player.getName() + " has just received " + reward + " " + (reward > 1 ? currencyP : currencyS) + " for voting.");
			if (broadcast) {
				v.getServer().broadcastMessage(formatOutput(broadcastMsg, player.getName()));
			}
		} else {
			player.sendMessage("Error giving money:" + r.errorMessage);
			log.info("[Vote4Cash] " + player.getName() + " could not be given money for voting. Here is the error: " + r.errorMessage);
		}
	}

	public void save(ArrayList<String> al) {
		if (dataFile.exists()) {
			dataFile.delete();
		}
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(dataFile));
			bw.write("#This is the payment pending list for the Vote4Cash Listener, add or remove from this data as you see fit.");
			bw.newLine();
			for (int i = 0; i < al.size(); i++) {
				bw.write(al.get(i));
				bw.newLine();
			}
			bw.close();
		} catch (Exception e) {
			log.severe("[Vote4Cash] Error saving list of pending players!");
		}
	}
}