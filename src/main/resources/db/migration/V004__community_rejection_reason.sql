-- Strengthen the already-applied V003 checks without changing migration history.
-- A rejected item must have a non-null reason. Existing invalid rows abort this
-- transactional migration so an operator can review them without data loss.
ALTER TABLE public.community_question
 ADD CONSTRAINT community_question_rejected_reason_required
 CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL);
ALTER TABLE public.community_answer
 ADD CONSTRAINT community_answer_rejected_reason_required
 CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL);
