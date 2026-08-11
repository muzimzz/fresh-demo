-- KEYS[1] = refreshToken:{role}:{id}
-- ARGV[1] = 기대하는 옛 값(쿠키로 들어온 refreshToken)
-- ARGV[2] = 새로 저장할 값
-- ARGV[3] = TTL(ms)
--
-- 저장된 값이 ARGV[1]과 같을 때만 ARGV[2]로 교체한다. Redis가 싱글스레드라 이 스크립트 전체가
-- 다른 명령 끼어들 틈 없이 원자적으로 실행된다 — 동시에 같은 옛 토큰으로 두 번 재발급 요청이 와도
-- 하나만 성공한다(Refresh Token Rotation race condition 방지).

local current = redis.call('GET', KEYS[1])
if current == ARGV[1] then
    redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
    return 1
else
    return 0
end
