CREATE TABLE project_release (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES software_project(id), sequence integer NOT NULL,
 label varchar(32) NOT NULL, note text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(project_id,sequence), UNIQUE(project_id,label));
CREATE TABLE project_release_file(release_id uuid NOT NULL REFERENCES project_release(id),delivery_id uuid NOT NULL REFERENCES project_delivery(id),PRIMARY KEY(release_id,delivery_id));
INSERT INTO project_release SELECT release_id,project_id,release_number,'v1.0.'||(release_number-1),min(note),min(created_at) FROM project_delivery WHERE release_id IS NOT NULL GROUP BY release_id,project_id,release_number;
INSERT INTO project_release_file SELECT release_id,id FROM project_delivery WHERE release_id IS NOT NULL;
