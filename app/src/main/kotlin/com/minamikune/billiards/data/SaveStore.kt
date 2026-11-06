package com.minamikune.billiards.data

import android.content.Context
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchSnapshot
import com.minamikune.billiards.game.PlayerInfo
import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.physics.BallGroup
import com.minamikune.billiards.physics.BallState
import com.minamikune.billiards.physics.Quat
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.physics.Vec3
import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON persistence for exactly one in-progress match. */
class SaveStore(context: Context) {
    private val prefs = context.getSharedPreferences("billiards_save", Context.MODE_PRIVATE)

    fun hasSave(): Boolean = prefs.contains(KEY)

    fun save(s: MatchSnapshot) {
        try {
            prefs.edit().putString(KEY, encode(s)).apply()
        } catch (_: Throwable) {
            // A failed save must not take the game down.
        }
    }

    fun load(): MatchSnapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            decode(JSONObject(raw))
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun encode(s: MatchSnapshot): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("mode", s.mode.name)
        o.put("state", s.state.name)
        o.put("current", s.currentPlayer)
        o.put("inHand", s.ballInHand)
        o.put("msg", s.message)
        o.put("winner", s.winner ?: JSONObject.NULL)
        o.put("shots", s.shots)
        o.put("aim", JSONArray().put(s.aimDirection.x).put(s.aimDirection.y))
        o.put("power", s.power)
        o.put("side", s.sideSpin)
        o.put("vertical", s.verticalSpin)
        o.put("lastPocketed", s.lastPocketed ?: JSONObject.NULL)
        val players = JSONArray()
        for (p in s.players) {
            players.put(JSONObject().apply {
                put("name", p.name)
                put("group", p.group?.name ?: JSONObject.NULL)
                put("pocketed", p.pocketed)
                put("fouls", p.fouls)
            })
        }
        o.put("players", players)
        val balls = JSONArray()
        for (b in s.balls) {
            balls.put(JSONObject().apply {
                put("id", b.id)
                put("pos", JSONArray().put(b.position.x).put(b.position.y))
                put("vel", JSONArray().put(b.velocity.x).put(b.velocity.y))
                put("omega", JSONArray().put(b.angularVelocity.x).put(b.angularVelocity.y).put(b.angularVelocity.z))
                put("q", JSONArray().put(b.orientation.w).put(b.orientation.x).put(b.orientation.y).put(b.orientation.z))
                put("active", b.active)
                put("asleep", b.asleep)
                put("pocket", b.pocketIndex)
            })
        }
        o.put("balls", balls)
        return o.toString()
    }

    private fun decode(o: JSONObject): MatchSnapshot? {
        if (o.optInt("version", 0) != 1) return null
        val mode = runCatching { GameMode.valueOf(o.getString("mode")) }.getOrNull() ?: return null
        val state = runCatching { SessionState.valueOf(o.getString("state")) }.getOrNull() ?: return null
        val ballsJson = o.optJSONArray("balls") ?: return null
        if (ballsJson.length() != 16) return null
        val balls = ArrayList<BallState>(16)
        for (i in 0 until ballsJson.length()) {
            val b = ballsJson.getJSONObject(i)
            val pos = b.getJSONArray("pos")
            val vel = b.getJSONArray("vel")
            val omega = b.getJSONArray("omega")
            val q = b.getJSONArray("q")
            balls.add(
                BallState(
                    id = b.getInt("id"),
                    position = Vec2(pos.getDouble(0), pos.getDouble(1)),
                    velocity = Vec2(vel.getDouble(0), vel.getDouble(1)),
                    angularVelocity = Vec3(omega.getDouble(0), omega.getDouble(1), omega.getDouble(2)),
                    orientation = Quat(q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3)),
                    active = b.getBoolean("active"),
                    asleep = b.getBoolean("asleep"),
                    pocketIndex = b.getInt("pocket"),
                ),
            )
        }
        val playersJson = o.optJSONArray("players") ?: JSONArray()
        val players = ArrayList<PlayerInfo>()
        for (i in 0 until playersJson.length()) {
            val p = playersJson.getJSONObject(i)
            val groupName = if (p.isNull("group")) null else p.getString("group")
            players.add(
                PlayerInfo(
                    name = p.optString("name", if (i == 0) "You" else "AI"),
                    group = groupName?.let { runCatching { BallGroup.valueOf(it) }.getOrNull() },
                    pocketed = p.optInt("pocketed", 0),
                    fouls = p.optInt("fouls", 0),
                ),
            )
        }
        if (players.isEmpty()) return null
        val aim = o.getJSONArray("aim")
        return MatchSnapshot(
            mode = mode,
            state = state,
            balls = balls,
            currentPlayer = o.optInt("current", 0),
            players = players,
            ballInHand = o.optBoolean("inHand"),
            message = o.optString("msg", ""),
            winner = if (o.isNull("winner")) null else o.optInt("winner"),
            shots = o.optInt("shots", 0),
            aimDirection = Vec2(aim.getDouble(0), aim.getDouble(1)),
            power = o.optDouble("power", 0.55),
            sideSpin = o.optDouble("side", 0.0),
            verticalSpin = o.optDouble("vertical", 0.0),
            aiThinking = state == SessionState.AI_THINKING,
            lastPocketed = if (o.isNull("lastPocketed")) null else o.optInt("lastPocketed"),
        )
    }

    companion object {
        private const val KEY = "match_v1"
    }
}
