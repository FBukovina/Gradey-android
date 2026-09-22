import { strict as assert } from "node:assert";
import { test } from "node:test";
import { isEduPageParentSession } from "./edupage-polling.ts";

test("parent credentials never poll a child through a mutable shared cookie", () => {
  assert.equal(isEduPageParentSession({ eduPage: { userID: "Rodic123", linkedStudents: [{id: "student1"}] } }), true);
  assert.equal(isEduPageParentSession({ eduPage: { userID: "parent4", linkedStudents: [] } }), true);
  assert.equal(isEduPageParentSession({ eduPage: { userID: "123", linkedStudents: [{id: "student1"}, {id: "student2"}] } }), true);
});

test("ordinary student and Bakalari credentials remain eligible", () => {
  assert.equal(isEduPageParentSession({ eduPage: { userID: "Student123", linkedStudents: [{id: "Student123"}] } }), false);
  assert.equal(isEduPageParentSession({ bakalari: { username: "parent123" } }), false);
});
