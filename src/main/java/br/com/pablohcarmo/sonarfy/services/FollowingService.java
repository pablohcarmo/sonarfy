package br.com.pablohcarmo.sonarfy.services;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FollowingService {
    private final EntityManager em;

    public FollowingService(EntityManager em) { this.em = em; }

    // Bloqueia e relê ambas as contas em ordem global antes de qualquer mutação.
    @Transactional
    public void change(long actorId, long targetId, boolean following) {
        boolean actorEligible = false, targetEligible = false;
        for (long id : new long[]{Math.min(actorId, targetId), Math.max(actorId, targetId)}) {
            var rows = em.createNativeQuery("select active, verified from users where user_id = :id for update")
                    .setParameter("id", id).getResultList();
            boolean eligible = !rows.isEmpty() && Boolean.TRUE.equals(((Object[]) rows.getFirst())[0])
                    && Boolean.TRUE.equals(((Object[]) rows.getFirst())[1]);
            if (id == actorId) actorEligible = eligible;
            if (id == targetId) targetEligible = eligible;
        }
        if (!actorEligible) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!targetEligible) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found.");
        if (actorId == targetId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot follow yourself.");
        String sql = following
                ? "insert into user_following (follower_id, following_id) values (:actor, :target) on conflict (follower_id, following_id) do nothing"
                : "delete from user_following where follower_id = :actor and following_id = :target";
        em.createNativeQuery(sql).setParameter("actor", actorId).setParameter("target", targetId).executeUpdate();
    }
}
